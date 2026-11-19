package com.example.hey_bro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Callbacks from AudioStreamer back to CoreVoiceService.
 */
interface AudioStreamerListener {
    fun onResponseComplete()
    fun onConnectionStateChanged(connected: Boolean)
    fun onInputTranscriptReceived(text: String)
    fun onSpokenTextReceived(text: String)
    fun onResponseTextReceived(text: String)
    fun onActionReceived(payload: String)
}

/**
 * Serverless Gemini Multimodal Live API Streamer.
 *
 * Connects directly to Google's BidiGenerateContent endpoint over WebSockets.
 * Streams real-time 16kHz PCM audio buffers, receives native 24kHz audio output
 * via AudioTrack, and routes tool-calling function invocations directly to the
 * Android Accessibility execution engine.
 */
class AudioStreamer(private val listener: AudioStreamerListener) {

    companion object {
        private const val TAG = "AudioStreamer"
        private const val PLAYBACK_SAMPLE_RATE = 24000 // Gemini Live outputs 24kHz 16-bit PCM Linear Mono
        private const val MAX_RETRIES = 6
        private const val DEFAULT_LIVE_MODEL = "models/gemini-3.8-live"
        private val RETRY_DELAYS_MS = longArrayOf(500, 1_000, 2_000, 4_000, 6_000, 10_000)
        private const val GEMINI_LIVE_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val PRIMARY_LIVE_MODEL: String get() = BuildConfig.GEMINI_LIVE_MODEL.ifBlank { DEFAULT_LIVE_MODEL }
        const val FALLBACK_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-latest"
        private val END_OF_TURN_MARKER = ByteArray(0)
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private var wsUrl: String = ""
    private var activeLiveModel: String = configuredLiveModel()
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val suppressCurrentTurnOutput = AtomicBoolean(false)
    private val groundingContextSent = AtomicBoolean(false)
    private var isGeminiLive = true
    private var powerManager: PowerManager? = null
    private var lastAudioActivityMs = System.currentTimeMillis()
    @Volatile private var sessionResumptionHandle: String? = null

    fun setPowerManager(pm: PowerManager?) {
        this.powerManager = pm
    }

    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            checkIdleTimeout()
            mainHandler.postDelayed(this, 5000L)
        }
    }

    private fun checkIdleTimeout() {
        if (!isConnected.get()) return
        val isScreenOn = powerManager?.isInteractive ?: true
        val silenceDuration = System.currentTimeMillis() - lastAudioActivityMs

        // If screen is off and we have 15 seconds of silence, close gracefully to save battery
        if (!isScreenOn && silenceDuration > 15_000L) {
            Log.i(TAG, "Screen off + 15s silence: gracefully closing Gemini WebSocket to preserve battery")
            stopAll()
        }
    }

    fun isConnected(): Boolean =
        isConnected.get() && (!isGeminiLive || setupComplete.get())

    fun isConnecting(): Boolean = isConnecting.get()
    private var retryCount = 0
    @Volatile private var reconnectRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // Disable automatic OkHttp ping to prevent 20s pong timeout drops on Gemini Live
        .build()

    // ── AudioTrack (Live 24kHz playback) ──────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private val audioTrackLock = Any()
    private val isTtsActive = AtomicBoolean(false)
    private val responseSpeakingNotified = AtomicBoolean(false)

    // ── Dedicated audio writer thread ─────────────────────────────────────────
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var audioWriterRunning = false
    private var audioWriterThread: Thread? = null

    private fun startAudioWriter() {
        if (audioWriterRunning) return
        audioWriterRunning = true
        audioWriterThread = Thread({
            while (audioWriterRunning) {
                try {
                    val data = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (data.isEmpty()) {
                        // End of turn marker: all audio chunks have been written to AudioTrack!
                        synchronized(audioTrackLock) {
                            val track = audioTrack
                            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                                try {
                                    Thread.sleep(250) // Allow internal audio buffer to drain to speaker
                                } catch (_: InterruptedException) {}
                            }
                        }
                        stopAudioTrack()
                        mainHandler.post {
                            listener.onResponseComplete()
                        }
                        continue
                    }

                    synchronized(audioTrackLock) {
                        val track = audioTrack
                        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                            if (!isTtsActive.getAndSet(true)) {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    try { track.play() } catch (_: Throwable) {}
                                }
                            }
                            try { track.write(data, 0, data.size) } catch (_: Throwable) {}
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "Audio writer error: ${e.message}")
                }
            }
        }, "AudioStreamer-Writer").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun stopAudioWriter() {
        audioWriterRunning = false
        audioWriterThread?.interrupt()
        audioWriterThread = null
        audioQueue.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun prewarm(urlOrApiKey: String) {
        if (isConnected.get() || isConnecting.get() || urlOrApiKey.isBlank()) return
        Log.i(TAG, "Speculative pre-warm of Gemini Live WebSocket triggered on speech onset")
        connect(urlOrApiKey)
    }

    fun connect(urlOrApiKey: String) {
        val trimmed = urlOrApiKey.trim()
        if (trimmed.isBlank()) return

        val targetUrl = if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            trimmed
        } else {
            "$GEMINI_LIVE_BASE_URL?key=$trimmed"
        }

        // If we already have an active or connecting socket to this exact URL, do not sever it!
        if (targetUrl == wsUrl && (isConnected.get() || isConnecting.get()) && webSocket != null) {
            Log.d(TAG, "Already connected/connecting to configured endpoint; maintaining socket")
            return
        }

        cancelReconnect()
        mainHandler.removeCallbacks(idleCheckRunnable)
        mainHandler.postDelayed(idleCheckRunnable, 5000L)
        isConnecting.set(true)
        lastAudioActivityMs = System.currentTimeMillis()
        isConnected.set(false)
        setupComplete.set(false)
        webSocket?.close(1000, "Backend changed")
        webSocket = null

        if (targetUrl != wsUrl) {
            sessionResumptionHandle = null
            activeLiveModel = configuredLiveModel()
        }
        wsUrl = targetUrl
        isGeminiLive = targetUrl.contains("googleapis.com")

        retryCount = 0
        buildAudioTrack()
        startAudioWriter()
        buildAndConnect()
    }

    fun sendAudioChunk(chunk: ByteArray) {
        if (!isConnected()) return
        lastAudioActivityMs = System.currentTimeMillis()

        if (isGeminiLive) {
            try {
                val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val msg = JSONObject().apply {
                    put("realtimeInput", JSONObject().apply {
                        put("mediaChunks", JSONArray().apply {
                            put(JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", base64Data)
                            })
                        })
                    })
                }
                val sent = webSocket?.send(msg.toString()) ?: false
                if (!sent) {
                    Log.w(TAG, "WebSocket send failed (backpressure/closed); triggering reconnect")
                    isConnected.set(false)
                    setupComplete.set(false)
                    listener.onConnectionStateChanged(false)
                    scheduleReconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send audio chunk to Gemini Live: ${e.message}")
                isConnected.set(false)
                setupComplete.set(false)
                listener.onConnectionStateChanged(false)
                scheduleReconnect()
            }
        } else {
            // Legacy raw binary fallback
            webSocket?.send(ByteString.of(*chunk))
        }
    }

    fun sendEndOfSpeech() {
        val message = if (isGeminiLive) {
            """{"realtimeInput":{"activityEnd":{}}}"""
        } else {
            """{"type":"END_OF_SPEECH"}"""
        }
        sendJson(message)
        suppressCurrentTurnOutput.set(false)
    }

    fun sendWakeWordTriggered() {
        groundingContextSent.set(false)
        suppressCurrentTurnOutput.set(false)
        responseSpeakingNotified.set(false)
        if (isGeminiLive) {
            sendJson("""{"realtimeInput":{"activityStart":{}}}""")
        } else {
            sendJson("""{"type":"WAKE_WORD_TRIGGERED"}""")
        }
    }

    fun interruptActiveTurn() {
        groundingContextSent.set(false)
        suppressCurrentTurnOutput.set(true)
        responseSpeakingNotified.set(false)
        flushAndPauseAudio()
        val message = if (isGeminiLive) {
            """{"realtimeInput":{"activityStart":{}}}"""
        } else {
            """{"type":"CANCEL"}"""
        }
        sendJson(message)
    }

    fun sendGroundingContext(uiHierarchy: String, recentNotifications: String) {
        if (!isGeminiLive || !isConnected() || !groundingContextSent.compareAndSet(false, true)) return
        val contextText = buildString {
            append("Context for the current request only. Do not treat this as another user request.\n")
            if (uiHierarchy.isNotBlank()) append("Active UI hierarchy: ").append(uiHierarchy.take(4000)).append('\n')
            if (recentNotifications.isNotBlank()) append("Recent notifications: ").append(recentNotifications.take(1200))
        }
        try {
            val message = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", contextText)))
                    }))
                    put("turnComplete", false)
                })
            }
            val sent = webSocket?.send(message.toString()) ?: false
            if (!sent) {
                groundingContextSent.set(false)
                Log.w(TAG, "Failed to send screen-aware grounding context")
            }
        } catch (e: Exception) {
            groundingContextSent.set(false)
            Log.w(TAG, "Could not serialize grounding context: ${e.message}")
        }
    }

    fun suppressCurrentTurnOutput() {
        suppressCurrentTurnOutput.set(true)
        audioQueue.clear()
        flushAndPauseAudio()
    }

    fun flushAndPauseAudio() {
        audioQueue.clear()
        synchronized(audioTrackLock) {
            try {
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.pause()
                }
                audioTrack?.flush()
            } catch (_: Throwable) {}
        }
        isTtsActive.set(false)
    }

    fun interruptPlayback() {
        flushAndPauseAudio()
    }

    fun stopAll() {
        isConnected.set(false)
        isConnecting.set(false)
        setupComplete.set(false)
        mainHandler.removeCallbacks(idleCheckRunnable)
        cancelReconnect()
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        stopAudioWriter()
        stopAudioTrack()
        releaseAudioTrack()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendJson(json: String) {
        if (isConnected.get()) webSocket?.send(json)
    }

    private fun configuredLiveModel(): String {
        val configured = BuildConfig.GEMINI_LIVE_MODEL.trim()
        return configured.takeIf {
            it == DEFAULT_LIVE_MODEL || it == FALLBACK_LIVE_MODEL
        } ?: DEFAULT_LIVE_MODEL
    }

    @Synchronized
    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun buildAndConnect() {
        if (wsUrl.isBlank()) return
        setupComplete.set(false)
        Log.d(TAG, "Connecting to Gemini Live WebSocket (attempt ${retryCount + 1}/$MAX_RETRIES)")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, SocketListener())
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (reconnectRunnable != null || wsUrl.isBlank()) return
        if (retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Giving up after $MAX_RETRIES retries.")
            listener.onConnectionStateChanged(false)
            return
        }
        val delay = RETRY_DELAYS_MS[retryCount.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
        Log.w(TAG, "Reconnecting in ${delay / 1000}s… (retry ${retryCount + 1})")
        retryCount++
        val retry = Runnable {
            synchronized(this) { reconnectRunnable = null }
            if (wsUrl.isBlank() || isConnected.get()) return@Runnable
            isConnecting.set(true)
            webSocket?.cancel()
            webSocket = null
            buildAndConnect()
        }
        reconnectRunnable = retry
        mainHandler.postDelayed(retry, delay)
    }

    // ── Setup Handshake for Gemini Live ───────────────────────────────────────

    private fun sendSetupHandshake() {
        if (!isGeminiLive) return

        try {
            val setupObj = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", activeLiveModel)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Aoede")
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "You are Hey Bro, an ultra-fast, direct, and attentive AI voice assistant. Listen carefully to user speech even when spoken quickly, softly, with diverse accents, or in noisy environments. When the user requests any device action (such as opening an app, placing a phone call, replying to a notification, sending a WhatsApp message, adjusting hardware settings, alarms, timers, media, or taking photos), invoke the corresponding tool function immediately. Keep all spoken replies natural, friendly, and strictly under 15 words.")
                        }))
                    })
                    put("inputAudioTranscription", JSONObject())
                    sessionResumptionHandle?.takeIf { it.isNotBlank() }?.let { handle ->
                        put("sessionResumption", JSONObject().put("handle", handle))
                    }
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("functionDeclarations", buildToolDeclarations())
                    }))
                })
            }

            Log.i(TAG, "Sending Gemini Live setup handshake")
            webSocket?.send(setupObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error constructing setup handshake: ${e.message}", e)
        }
    }

    private fun buildToolDeclarations(): JSONArray {
        val tools = JSONArray()

        // 1. makeCall
        tools.put(JSONObject().apply {
            put("name", "makeCall")
            put("description", "Places a phone call with optional SIM slot selection (1 or 2).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("number", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Phone number or contact name to call")
                    })
                    put("simSlot", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "SIM slot index: 1 or 2")
                    })
                })
                put("required", JSONArray().put("number"))
            })
        })

        // 2. replyNotification
        tools.put(JSONObject().apply {
            put("name", "replyNotification")
            put("description", "Sends a background reply to an active notification without opening the app.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("packageName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Target app package (e.g. com.whatsapp, org.telegram.messenger)")
                    })
                    put("contact", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Sender or contact name")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The reply text message payload")
                    })
                })
                put("required", JSONArray().put("message"))
            })
        })

        // 3. controlHardware
        tools.put(JSONObject().apply {
            put("name", "controlHardware")
            put("description", "Controls flashlight, wifi, bluetooth, volume, brightness, or airplane mode.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("device", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Device target: flashlight, wifi, bluetooth, volume, brightness, airplane, hotspot")
                    })
                    put("state", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Action/state: on, off, up, down, mute, toggle")
                    })
                })
                put("required", JSONArray().put("device").put("state"))
            })
        })

        // 4. openApp
        tools.put(JSONObject().apply {
            put("name", "openApp")
            put("description", "Launches any installed Android application by name.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Human-readable name of the app (e.g. WhatsApp, YouTube, Spotify, Camera)")
                    })
                })
                put("required", JSONArray().put("appName"))
            })
        })

        // 5. sendWhatsApp
        tools.put(JSONObject().apply {
            put("name", "sendWhatsApp")
            put("description", "Sends a direct WhatsApp message to a contact.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contact", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Contact name or phone number")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Message text content")
                    })
                })
                put("required", JSONArray().put("message"))
            })
        })

        // 6. readNotifications
        tools.put(JSONObject().apply {
            put("name", "readNotifications")
            put("description", "Reads the latest unread notifications and messages aloud.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contact", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional contact name filter")
                    })
                })
            })
        })

        // 7. setAlarm
        tools.put(JSONObject().apply {
            put("name", "setAlarm")
            put("description", "Sets a system alarm clock.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("hour", JSONObject().apply { put("type", "INTEGER"); put("description", "Hour (0-23)") })
                    put("minute", JSONObject().apply { put("type", "INTEGER"); put("description", "Minute (0-59)") })
                    put("message", JSONObject().apply { put("type", "STRING"); put("description", "Alarm label") })
                })
                put("required", JSONArray().put("hour").put("minute"))
            })
        })

        // 8. setTimer
        tools.put(JSONObject().apply {
            put("name", "setTimer")
            put("description", "Sets a countdown timer.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("seconds", JSONObject().apply { put("type", "INTEGER"); put("description", "Duration in seconds") })
                    put("message", JSONObject().apply { put("type", "STRING"); put("description", "Timer label") })
                })
                put("required", JSONArray().put("seconds"))
            })
        })

        // 9. takePhoto
        tools.put(JSONObject().apply {
            put("name", "takePhoto")
            put("description", "Opens camera and captures a photo.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("delaySeconds", JSONObject().apply { put("type", "INTEGER"); put("description", "Countdown delay") })
                })
            })
        })

        // 10. takeSelfie
        tools.put(JSONObject().apply {
            put("name", "takeSelfie")
            put("description", "Opens front camera and captures a selfie.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("delaySeconds", JSONObject().apply { put("type", "INTEGER"); put("description", "Countdown delay") })
                })
            })
        })

        // 11. screenshot
        tools.put(JSONObject().apply {
            put("name", "screenshot")
            put("description", "Captures the phone screen.")
            put("parameters", JSONObject().apply { put("type", "OBJECT"); put("properties", JSONObject()) })
        })

        // 12. searchWeb
        tools.put(JSONObject().apply {
            put("name", "searchWeb")
            put("description", "Searches the web in browser.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "STRING"); put("description", "Search query") })
                })
                put("required", JSONArray().put("query"))
            })
        })

        // 13. openMaps
        tools.put(JSONObject().apply {
            put("name", "openMaps")
            put("description", "Opens Google Maps navigation.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "STRING"); put("description", "Destination or place") })
                })
                put("required", JSONArray().put("query"))
            })
        })

        // 14. playMedia
        tools.put(JSONObject().apply {
            put("name", "playMedia")
            put("description", "Plays song or video in YouTube or Spotify.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "STRING"); put("description", "Song or artist name") })
                    put("app", JSONObject().apply { put("type", "STRING"); put("description", "Target app (YouTube/Spotify)") })
                })
                put("required", JSONArray().put("query"))
            })
        })

        return tools
    }

    private fun sendToolResponse(callId: String, output: JSONObject) {
        try {
            val responseMsg = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", callId)
                            put("response", JSONObject().apply {
                                put("output", output)
                            })
                        })
                    })
                })
            }
            Log.d(TAG, "Sending toolResponse for $callId: $responseMsg")
            webSocket?.send(responseMsg.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send toolResponse: ${e.message}")
        }
    }

    // ── AudioTrack ────────────────────────────────────────────────────────────

    private fun buildAudioTrack() {
        synchronized(audioTrackLock) {
            releaseAudioTrackInternal()
            val sampleRate = if (isGeminiLive) PLAYBACK_SAMPLE_RATE else 16000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)
            // Use 6x buffer to prevent underruns during live audio streaming

            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 6)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.pause()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to build AudioTrack: ${e.message}")
                audioTrack = null
            }
        }
    }

    private fun releaseAudioTrack() {
        synchronized(audioTrackLock) {
            releaseAudioTrackInternal()
        }
    }

    private fun releaseAudioTrackInternal() {
        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
            }
            audioTrack?.release()
        } catch (_: Throwable) {}
        audioTrack = null
        isTtsActive.set(false)
    }

    private fun stopAudioTrack() {
        synchronized(audioTrackLock) {
            try {
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.pause()
                }
                audioTrack?.flush()
            } catch (_: Throwable) {}
        }
        isTtsActive.set(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WebSocket listener
    // ─────────────────────────────────────────────────────────────────────────

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (this@AudioStreamer.webSocket !== webSocket) return
            Log.i(TAG, "WebSocket connected successfully ✓")
            isConnected.set(true)
            isConnecting.set(false)
            if (isGeminiLive) {
                sendSetupHandshake()
            } else {
                retryCount = 0
                listener.onConnectionStateChanged(true)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (this@AudioStreamer.webSocket !== webSocket) return
            // Raw binary audio fallback — route through dedicated writer thread
            audioQueue.offer(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (this@AudioStreamer.webSocket !== webSocket) return
            try {
                val root = JSONObject(text)

                root.optJSONObject("sessionResumptionUpdate")
                    ?.optString("newHandle")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sessionResumptionHandle = it }

                // Mark setup as complete when we receive the first server message
                // (setupComplete response or first serverContent)
                if (root.has("setupComplete") || root.has("serverContent") || root.has("toolCall")) {
                    if (!setupComplete.getAndSet(true)) {
                        retryCount = 0
                        Log.i(TAG, "Gemini Live setup handshake confirmed ✓")
                        listener.onConnectionStateChanged(true)
                    }
                }

                // 1. Check for Gemini Live Server Content (Audio / Text turns)
                if (root.has("serverContent")) {
                    val serverContent = root.getJSONObject("serverContent")

                    // Natural barge-in / Interrupted
                    if (serverContent.optBoolean("interrupted", false)) {
                        Log.i(TAG, "Gemini Live model interrupted by user speech")
                        responseSpeakingNotified.set(false)
                        flushAndPauseAudio()
                    }

                    serverContent.optJSONObject("inputTranscription")
                        ?.optString("text")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(listener::onInputTranscriptReceived)

                    // Model Turn (Audio chunks / Transcripts)
                    if (!suppressCurrentTurnOutput.get() && serverContent.has("modelTurn")) {
                        val modelTurn = serverContent.getJSONObject("modelTurn")
                        val parts = modelTurn.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)

                                // Inline Audio (Base64 24kHz PCM)
                                if (part.has("inlineData")) {
                                    val inlineData = part.getJSONObject("inlineData")
                                    val base64 = inlineData.optString("data", "")
                                    if (base64.isNotBlank()) {
                                        val pcmBytes = Base64.decode(base64, Base64.DEFAULT)
                                        // Route through dedicated writer thread to avoid blocking WS reader
                                        audioQueue.offer(pcmBytes)
                                        if (responseSpeakingNotified.compareAndSet(false, true)) {
                                            listener.onSpokenTextReceived("Speaking…")
                                        }
                                    }
                                }

                                // Spoken text transcript
                                if (part.has("text")) {
                                    val spokenText = part.getString("text")
                                    if (spokenText.isNotBlank()) {
                                        listener.onResponseTextReceived(spokenText)
                                    }
                                }
                            }
                        }
                    }

                    // Turn complete
                    if (serverContent.optBoolean("turnComplete", false)) {
                        handleResponseComplete()
                    }
                }

                // 2. Check for Gemini Live Tool Calls
                if (root.has("toolCall")) {
                    val toolCall = root.getJSONObject("toolCall")
                    val functionCalls = toolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            if (suppressCurrentTurnOutput.get()) {
                                sendToolResponse(
                                    call.optString("id"),
                                    JSONObject().put("status", "handled_locally")
                                )
                                continue
                            }
                            val name = call.getString("name")
                            val callId = call.getString("id")
                            val args = call.optJSONObject("args") ?: JSONObject()

                            Log.i(TAG, "Received Live tool call: $name (id: $callId)")

                            val actionPayload = JSONObject().apply {
                                put("action", name)
                                put("params", args)
                                put("type", "ACTION")
                            }

                            // Dispatch action to Accessibility Engine
                            listener.onActionReceived(actionPayload.toString())

                            // Return tool response to complete Gemini turn
                            sendToolResponse(callId, JSONObject().apply { put("status", "success") })
                        }
                    }
                }

                // 3. Legacy compatibility (Custom backend fallback)
                when (root.optString("type")) {
                    "RESPONSE_COMPLETE" -> handleResponseComplete()
                    "TRANSCRIPT" -> listener.onResponseTextReceived(root.optString("text"))
                    "SPOKEN_TEXT" -> listener.onSpokenTextReceived(root.optString("text"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error from live socket: ${e.message}", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@AudioStreamer.webSocket !== webSocket) return
            Log.e(TAG, "Gemini Live WebSocket failure: ${t.message} (HTTP ${response?.code})")
            if ((response?.code == 404 || response?.code == 429) && activeLiveModel != FALLBACK_LIVE_MODEL) {
                Log.w(TAG, "Attempting connection fallback from $activeLiveModel to $FALLBACK_LIVE_MODEL")
                activeLiveModel = FALLBACK_LIVE_MODEL
                sessionResumptionHandle = null
            }
            isConnected.set(false)
            isConnecting.set(false)
            setupComplete.set(false)
            listener.onConnectionStateChanged(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@AudioStreamer.webSocket !== webSocket) return
            Log.i(TAG, "Gemini Live WebSocket closed ($code): $reason")
            isConnected.set(false)
            isConnecting.set(false)
            setupComplete.set(false)
            listener.onConnectionStateChanged(false)

            if ((code == 1008 || code == 1011) && activeLiveModel != FALLBACK_LIVE_MODEL) {
                Log.w(TAG, "Quota or policy closure ($code); attempting fallback to $FALLBACK_LIVE_MODEL")
                activeLiveModel = FALLBACK_LIVE_MODEL
                sessionResumptionHandle = null
            }
            scheduleReconnect()
        }
    }

    private fun handleResponseComplete() {
        if (suppressCurrentTurnOutput.getAndSet(false)) {
            audioQueue.clear()
            responseSpeakingNotified.set(false)
            return
        }
        responseSpeakingNotified.set(false)
        if (audioQueue.isEmpty() && !isTtsActive.get()) {
            mainHandler.post {
                stopAudioTrack()
                listener.onResponseComplete()
            }
        } else {
            audioQueue.offer(END_OF_TURN_MARKER)
        }
    }
}
