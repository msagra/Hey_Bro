package com.example.hey_bro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
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
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 3_000, 5_000, 8_000, 12_000)
        private const val SETUP_HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val GEMINI_LIVE_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private var wsUrl: String = ""
    private var apiKey: String = ""
    private val isConnected = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private var isGeminiLive = true

    fun isConnected(): Boolean = isConnected.get()
    private var retryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // ── AudioTrack (Live 24kHz playback) ──────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private val isTtsActive = AtomicBoolean(false)

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun connect(urlOrApiKey: String) {
        mainHandler.removeCallbacksAndMessages(null)
        isConnected.set(false)
        setupComplete.set(false)
        webSocket?.close(1000, "Backend changed")
        webSocket = null

        if (urlOrApiKey.startsWith("ws://") || urlOrApiKey.startsWith("wss://")) {
            wsUrl = urlOrApiKey
            isGeminiLive = urlOrApiKey.contains("googleapis.com")
        } else {
            apiKey = urlOrApiKey.trim()
            wsUrl = "$GEMINI_LIVE_BASE_URL?key=$apiKey"
            isGeminiLive = true
        }

        retryCount = 0
        buildAudioTrack()
        buildAndConnect()
    }

    fun sendAudioChunk(chunk: ByteArray) {
        if (!isConnected.get()) return

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
        if (!isGeminiLive) {
            sendJson("""{"type":"END_OF_SPEECH"}""")
        }
    }

    fun sendWakeWordTriggered() {
        if (!isGeminiLive) {
            sendJson("""{"type":"WAKE_WORD_TRIGGERED"}""")
        }
    }

    fun flushAndPauseAudio() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        isTtsActive.set(false)
    }

    fun interruptPlayback() {
        flushAndPauseAudio()
    }

    fun stopAll() {
        isConnected.set(false)
        setupComplete.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        stopAudioTrack()
        releaseAudioTrack()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendJson(json: String) {
        if (isConnected.get()) webSocket?.send(json)
    }

    private fun buildAndConnect() {
        if (wsUrl.isBlank()) return
        setupComplete.set(false)
        Log.d(TAG, "Connecting to Gemini Live WebSocket (attempt ${retryCount + 1}/$MAX_RETRIES)")
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

    // ── Setup Handshake for Gemini Live ───────────────────────────────────────

    private fun sendSetupHandshake() {
        if (!isGeminiLive) return

        try {
            val setupObj = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", "models/gemini-2.0-flash-exp")
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
                            put("text", "You are Hey Bro, an ultra-fast, direct voice assistant. Keep all spoken replies natural and strictly under 15 words. When the user requests any device action (such as opening an app, placing a phone call, replying to a notification, sending a WhatsApp message, or adjusting hardware settings), invoke the corresponding tool function immediately.")
                        }))
                    })
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
        releaseAudioTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
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
            Log.i(TAG, "WebSocket connected to Gemini Live ✓")
            isConnected.set(true)
            retryCount = 0
            listener.onConnectionStateChanged(true)
            sendSetupHandshake()
            // Validate that the setup handshake completes within timeout
            if (isGeminiLive) {
                mainHandler.postDelayed({
                    if (isConnected.get() && !setupComplete.get()) {
                        Log.w(TAG, "Setup handshake not acknowledged within ${SETUP_HANDSHAKE_TIMEOUT_MS}ms; reconnecting")
                        webSocket.close(1000, "Setup timeout")
                        isConnected.set(false)
                        listener.onConnectionStateChanged(false)
                        scheduleReconnect()
                    }
                }, SETUP_HANDSHAKE_TIMEOUT_MS)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Raw binary audio fallback
            val data = bytes.toByteArray()
            if (!isTtsActive.getAndSet(true)) {
                audioTrack?.play()
            }
            audioTrack?.write(data, 0, data.size)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val root = JSONObject(text)

                // Mark setup as complete when we receive the first server message
                // (setupComplete response or first serverContent)
                if (root.has("setupComplete") || root.has("serverContent") || root.has("toolCall")) {
                    if (!setupComplete.getAndSet(true)) {
                        Log.i(TAG, "Gemini Live setup handshake confirmed ✓")
                    }
                }

                // 1. Check for Gemini Live Server Content (Audio / Text turns)
                if (root.has("serverContent")) {
                    val serverContent = root.getJSONObject("serverContent")

                    // Natural barge-in / Interrupted
                    if (serverContent.optBoolean("interrupted", false)) {
                        Log.i(TAG, "Gemini Live model interrupted by user speech")
                        stopAudioTrack()
                    }

                    // Model Turn (Audio chunks / Transcripts)
                    if (serverContent.has("modelTurn")) {
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
                                        if (!isTtsActive.getAndSet(true)) {
                                            audioTrack?.play()
                                        }
                                        audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                                        listener.onSpokenTextReceived("Speaking…")
                                    }
                                }

                                // Spoken text transcript
                                if (part.has("text")) {
                                    val spokenText = part.getString("text")
                                    if (spokenText.isNotBlank()) {
                                        listener.onTranscriptReceived(spokenText)
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
                            val name = call.getString("name")
                            val callId = call.getString("id")
                            val args = call.optJSONObject("args") ?: JSONObject()

                            Log.i(TAG, "Received Live tool call: $name (id: $callId, args: $args)")

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
                    "TRANSCRIPT" -> listener.onTranscriptReceived(root.optString("text"))
                    "SPOKEN_TEXT" -> listener.onSpokenTextReceived(root.optString("text"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error from live socket: ${e.message}", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Gemini Live WebSocket failure: ${t.message}")
            isConnected.set(false)
            listener.onConnectionStateChanged(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Gemini Live WebSocket closed ($code): $reason")
            isConnected.set(false)
            listener.onConnectionStateChanged(false)
        }
    }

    private fun handleResponseComplete() {
        mainHandler.postDelayed({
            stopAudioTrack()
        }, 500)
        listener.onResponseComplete()
    }
}
