package com.example.hey_bro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Callbacks from AudioStreamer back to CoreVoiceService.
 */
interface AudioStreamerListener {
    fun onResponseComplete()
    fun onConnectionStateChanged(connected: Boolean)
    fun onTranscriptReceived(text: String)
    fun onSpokenTextReceived(text: String)
    fun onActionReceived(payload: String)
}

/**
 * Manages the WebSocket connection to the Hey Bro backend.
 *
 * Responsibilities:
 *  - Send PCM audio chunks, wake-word trigger signals, and end-of-speech markers.
 *  - Receive raw PCM TTS audio and play it through AudioTrack.
 *  - Parse JSON control messages (RESPONSE_COMPLETE, TRANSCRIPT, etc.).
 *  - Auto-reconnect on failure with exponential back-off.
 */
class AudioStreamer(private val listener: AudioStreamerListener) {

    companion object {
        private const val TAG             = "AudioStreamer"
        private const val SAMPLE_RATE     = 16000
        private const val MAX_RETRIES     = 6
        private val RETRY_DELAYS_MS       = longArrayOf(3_000, 5_000, 10_000, 15_000, 20_000, 30_000)
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private var wsUrl: String = ""
    private val isConnected = AtomicBoolean(false)

    fun isConnected(): Boolean = isConnected.get()
    private var retryCount  = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // ── AudioTrack (TTS playback) ─────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private val isTtsActive = AtomicBoolean(false)

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun connect(url: String) {
        wsUrl      = url
        retryCount = 0
        buildAndConnect()
        buildAudioTrack()
    }

    fun sendAudioChunk(chunk: ByteArray) {
        if (isConnected.get()) {
            webSocket?.send(chunk.toByteString())
        }
    }

    fun sendEndOfSpeech() {
        sendJson("""{"type":"END_OF_SPEECH"}""")
    }

    fun sendWakeWordTriggered() {
        sendJson("""{"type":"WAKE_WORD_TRIGGERED"}""")
    }

    fun sendScreenContext(content: String) {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        sendJson("""{"type":"SCREEN_CONTEXT","content":"$escaped"}""")
    }

    fun stopAll() {
        isConnected.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        stopAudioTrack()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendJson(json: String) {
        if (isConnected.get()) webSocket?.send(json)
    }

    private fun buildAndConnect() {
        if (wsUrl.isBlank()) return
        Log.d(TAG, "Connecting to $wsUrl (attempt ${retryCount + 1}/$MAX_RETRIES)")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, SocketListener())
    }

    private fun scheduleReconnect() {
        if (retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Giving up after $MAX_RETRIES retries.")
            listener.onConnectionStateChanged(false)
            return
        }
        val delay = RETRY_DELAYS_MS[retryCount.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
        Log.w(TAG, "Reconnecting in ${delay / 1000}s… (retry ${retryCount + 1})")
        retryCount++
        mainHandler.postDelayed({ buildAndConnect() }, delay)
    }

    // ── AudioTrack ────────────────────────────────────────────────────────────

    private fun buildAudioTrack() {
        releaseAudioTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Start in paused state; play() is called on first TTS chunk
        audioTrack?.pause()
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        isTtsActive.set(false)
    }

    private fun stopAudioTrack() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        isTtsActive.set(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WebSocket listener
    // ─────────────────────────────────────────────────────────────────────────

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected ✓")
            isConnected.set(true)
            retryCount = 0
            listener.onConnectionStateChanged(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Raw PCM audio from TTS – write straight to AudioTrack
            val data = bytes.toByteArray()
            if (!isTtsActive.getAndSet(true)) {
                // First chunk of a new TTS response – start playback
                audioTrack?.play()
            }
            audioTrack?.write(data, 0, data.size)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "← $text")
            try {
                val obj = org.json.JSONObject(text)
                when (obj.optString("type")) {
                    "RESPONSE_COMPLETE" -> handleResponseComplete()
                    "TRANSCRIPT"        -> listener.onTranscriptReceived(obj.optString("text"))
                    "SPOKEN_TEXT"       -> listener.onSpokenTextReceived(obj.optString("text"))
                    else                -> listener.onActionReceived(text) // device action
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error: ${e.message}")
                // Pass through as a raw action payload
                listener.onActionReceived(text)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            isConnected.set(false)
            listener.onConnectionStateChanged(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed ($code): $reason")
            isConnected.set(false)
        }
    }

    private fun handleResponseComplete() {
        // Give the AudioTrack ~600 ms to drain its buffer before pausing
        mainHandler.postDelayed({
            stopAudioTrack()
        }, 600)
        listener.onResponseComplete()
    }
}
