package com.example.hey_bro

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class ServiceState { IDLE, LISTENING, PROCESSING, SPEAKING }

/**
 * CoreVoiceService – fully local, zero-network AI voice assistant service.
 */
class CoreVoiceService : Service(),
    LocalSttEngine.Listener,
    LocalTtsEngine.Listener {

    // ── Companion ─────────────────────────────────────────────────────────────
    companion object {
        private const val TAG             = "CoreVoiceService"
        private const val CHANNEL_ID      = "HeyBroChannelSilentV3"
        private const val LEGACY_CHANNEL_ID_V2 = "HeyBroChannelSilentV2"
        private const val LEGACY_CHANNEL_ID_V1 = "HeyBroChannel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE     = 16000
        private const val KEY_BACKEND_WS  = "backend_ws"
        private const val DEFAULT_BACKEND_WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val DEFAULT_GEMINI_KEY: String get() = BuildConfig.GEMINI_API_KEY

        var activeEngineName: String = "None"
        var eventSink: EventChannel.EventSink? = null

        @Volatile private var activeInstance: CoreVoiceService? = null

        fun updateBackendWs(url: String) {
            activeInstance?.applyBackendWs(url)
        }

        fun reportActionProgress(step: Int, status: String, error: String) {
            activeInstance?.pushEvent(mapOf(
                "type" to "action_progress",
                "step" to step.toString(),
                "stepStatus" to status,
                "stepError" to error.take(160)
            ))
        }

        fun syncStateToFlutter() {
            activeInstance?.publishCurrentState()
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile private var serviceState = ServiceState.IDLE
    private var sttFailureCount = 0
    private var sttRetryInFlight = false
    private var sttRetryRunnable: Runnable? = null

    private fun cancelSttRetry() {
        sttRetryInFlight = false
        sttRetryRunnable?.let {
            mainHandler.removeCallbacks(it)
            sttRetryRunnable = null
        }
    }

    // Audio focus helpers
    private var audioFocusRequested = false
    private var audioFocusRequestCompat: Any? = null

    private fun requestAudioFocus(): Boolean {
        try {
            if (audioFocusRequested) return true
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val afReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                val res = am.requestAudioFocus(afReq)
                audioFocusRequestCompat = afReq
                audioFocusRequested = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                audioFocusRequested = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            Log.d(TAG, "Audio focus requested: $audioFocusRequested")
            return audioFocusRequested
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed: ${e.message}")
            audioFocusRequested = false
            return false
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (!audioFocusRequested) return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val afReq = audioFocusRequestCompat as? android.media.AudioFocusRequest
                if (afReq != null) am.abandonAudioFocusRequest(afReq)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            audioFocusRequested = false
            audioFocusRequestCompat = null
            Log.d(TAG, "Audio focus abandoned")
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
    }

    // ── Components ────────────────────────────────────────────────────────────
    private lateinit var overlayManager: OverlayManager
    private lateinit var sttEngine:      LocalSttEngine
    private lateinit var ttsEngine:      LocalTtsEngine
    private lateinit var prefs: SharedPreferences
    private var wakeWordManager: WakeWordManager? = null
    private var sileroVad:       SileroVad?       = null
    private var audioStreamer:   AudioStreamer?   = null
    private var backendWsUrl:    String? = null
    private var awaitingCommandAfterAck: Boolean = false
    @Volatile private var lastBackendTranscript = ""
    private val backendResponseLock = Any()
    private var lastBackendResponse = ""
    private val dispatchedActionLock = Any()
    private val dispatchedActionSignatures = mutableSetOf<String>()
    private val fastPathSignaturesThisTurn = mutableSetOf<String>()
    private var lastHandledSttTranscript = ""
    @Volatile private var serviceDestroyed = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backendResponseHandler = mainHandler
    private var backendFallbackPending = false
    private val sessionId = UUID.randomUUID().toString()
    private val eventSequence = AtomicLong(0L)
    @Volatile private var pendingSensitiveAction: String? = null

    private val backendFallbackRunnable = Runnable {
        if (!serviceDestroyed && backendFallbackPending && serviceState == ServiceState.LISTENING) {
            backendFallbackPending = false
            Log.w(TAG, "Backend response timeout; falling back to local STT")
            requestAudioFocus()
            sttEngine.startListening()
        }
    }

    // ── Audio loop ────────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private val audioRecordLock = Any()
    private var audioJob:    Job?         = null
    private var captureJob: Job? = null
    private var localBargeInJob: Job? = null
    @Volatile private var captureAudioRecord: AudioRecord? = null

    private val startWakeWordRunnable = Runnable {
        if (!serviceDestroyed && serviceState == ServiceState.IDLE) {
            startWakeWordLoop()
        }
    }

    private var stateWatchdogJob: Job? = null

    private fun scheduleStateWatchdog(timeoutMs: Long, expectedState: ServiceState) {
        stateWatchdogJob?.cancel()
        stateWatchdogJob = scope.launch {
            delay(timeoutMs)
            if (isActive && serviceState == expectedState) {
                Log.w(TAG, "Watchdog timeout after ${timeoutMs}ms in state $expectedState; recovering to IDLE")
                resetToIdle()
                pushEvent(mapOf("state" to "IDLE", "watchdogRecovered" to "true"))
                overlayManager.hideDelayed(300)
            }
        }
    }

    private fun cancelStateWatchdog() {
        stateWatchdogJob?.cancel()
        stateWatchdogJob = null
    }

    private fun stopCaptureAudio() {
        captureJob?.cancel()
        captureJob = null
        val rec = captureAudioRecord
        captureAudioRecord = null
        if (rec != null) {
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: Throwable) {}
            try {
                rec.release()
            } catch (_: Throwable) {}
        }
    }
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var lastWakeAtMs = 0L
    private var lastFailedListenAtMs = 0L

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed = false
        Log.d(TAG, "Service created")

        // Acquire CPU Partial WakeLock for Dimensity 9400+ efficiency cores on screen-off
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            cpuWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyBro:WakeCPU")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired HeyBro:WakeCPU partial wake lock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire CPU wake lock: ${e.message}")
        }
        createNotificationChannel()
        prefs = getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)
        activeInstance = this

        overlayManager = OverlayManager(this)
        sttEngine      = LocalSttEngine(this)
        ttsEngine      = LocalTtsEngine(this)
        LocalCommandProcessor.initialize(applicationContext)

        sttEngine.initialize(this)
        ttsEngine.initialize(this)

        try {
            backendWsUrl = prefs.getString(KEY_BACKEND_WS, DEFAULT_BACKEND_WS)
            if (!backendWsUrl.isNullOrBlank()) {
                configureBackendStreamer()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioStreamer init failed: ${e.message}")
            audioStreamer = null
            backendWsUrl = null
        }

        initWakeWordEngine()
        initVAD()
        mainHandler.post {
            if (!serviceDestroyed) publishCurrentState()
        }
    }

    private fun configureBackendStreamer() {
        val apiKey = prefs.getString("gemini_api_key", "")?.trim().let { if (it.isNullOrBlank()) DEFAULT_GEMINI_KEY else it }
        val configuredWs = prefs.getString(KEY_BACKEND_WS, DEFAULT_BACKEND_WS)?.trim() ?: ""
        if (configuredWs.equals("offline", ignoreCase = true)) {
            backendWsUrl = null
            audioStreamer?.stopAll()
            audioStreamer = null
            return
        }
        val target = if (apiKey.isNotBlank() && (configuredWs.isBlank() || configuredWs.contains("googleapis.com"))) {
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        } else if (configuredWs.isNotBlank()) {
            if (configuredWs.contains("googleapis.com")) {
                if (apiKey.isNotBlank()) {
                    if (configuredWs.contains("key=")) configuredWs else "$configuredWs?key=$apiKey"
                } else {
                    DEFAULT_BACKEND_WS
                }
            } else {
                configuredWs
            }
        } else if (apiKey.isNotBlank()) {
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        } else {
            DEFAULT_BACKEND_WS
        }
        backendWsUrl = target
        audioStreamer = AudioStreamer(object : AudioStreamerListener {
            override fun onResponseComplete() {
                if (serviceState != ServiceState.SPEAKING) return
                cancelBackendFallback()
                abandonAudioFocus()
                resetToIdle()
                pushEvent(mapOf(
                    "state" to "IDLE",
                    "responseComplete" to "true",
                    "transcript" to lastBackendTranscript,
                    "response" to currentBackendResponse()
                ))
                overlayManager.hideDelayed(300)
            }
            override fun onConnectionStateChanged(connected: Boolean) {
                Log.i(TAG, "AudioStreamer connected=$connected")
                pushEvent(mapOf(
                    "state" to (if (connected) "CONNECTED" else "DISCONNECTED"),
                    "wsConnected" to (if (connected) "true" else "false")
                ))
                if (!connected && serviceState == ServiceState.LISTENING) {
                    backendFallbackPending = false
                    backendResponseHandler.removeCallbacks(backendFallbackRunnable)
                    stopCaptureAudio()
                    requestAudioFocus()
                    sttEngine.startListening()
                }
            }
            override fun onResponseTextReceived(text: String) {
                if (text.trim().matches(Regex("^[^\\p{L}\\p{N}]+$"))) {
                    Log.d(TAG, "Ignoring punctuation-only response text")
                    return
                }
                mainHandler.post {
                    if (serviceDestroyed || text.isBlank()) return@post
                    cancelBackendFallback()
                    appendBackendResponse(text)
                    transitionTo(ServiceState.SPEAKING)
                    val response = currentBackendResponse()
                    pushEvent(mapOf(
                        "state" to "PLAYING",
                        "transcript" to lastBackendTranscript,
                        "response" to response,
                        "responseStreaming" to "true"
                    ))
                    overlayManager.show(OverlayManager.State.PLAYING, response.takeLast(40))
                }
            }
            override fun onInputTranscriptReceived(text: String) {
                mainHandler.post {
                    if (serviceDestroyed) return@post
                    if (isScreenAwareRequest(text)) {
                        val ui = DeviceOperatorService.instance?.serializeActiveUiHierarchy().orEmpty()
                        val notifications = HeyBroNotificationListenerService.getCompactRecentSummaries()
                        audioStreamer?.sendGroundingContext(ui, notifications)
                    }
                    lastBackendTranscript = text
                    handleInputTranscript(text)
                }
            }
            override fun onSpokenTextReceived(text: String) {
                mainHandler.post {
                    if (serviceDestroyed) return@post
                    cancelBackendFallback()
                    transitionTo(ServiceState.SPEAKING)
                    if (text != "Speaking…") {
                        appendBackendResponse(text)
                    }
                    val response = currentBackendResponse()
                    pushEvent(buildMap {
                        put("state", "PLAYING")
                        put("transcript", lastBackendTranscript)
                        if (text != "Speaking…") {
                            put("response", response)
                            put("responseStreaming", "true")
                        }
                    })
                    overlayManager.show(OverlayManager.State.PLAYING, response.takeLast(40).ifBlank { text.take(40) })
                }
            }

            private fun handleInputTranscript(text: String) {
                if (serviceState !in setOf(ServiceState.LISTENING, ServiceState.PROCESSING, ServiceState.SPEAKING)) return
                if (handlePendingConfirmation(text)) return
                val result = LocalCommandProcessor.checkFastPath(text) ?: return
                val action = result.action ?: return
                val signature = actionSignature(action.toString())
                synchronized(dispatchedActionLock) {
                    if (signature.isNotBlank() &&
                        (signature in fastPathSignaturesThisTurn || signature in dispatchedActionSignatures)
                    ) {
                        return
                    }
                    if (signature.isNotBlank()) fastPathSignaturesThisTurn.add(signature)
                }

                Log.i(TAG, "Executing local fast-path action while Gemini Live remains active")
                if (requiresVoiceConfirmation(action.toString())) {
                    requestVoiceConfirmation(action.toString())
                    return
                }
                cancelBackendFallback()
                closeLiveInputForLocalResponse()
                dispatchDeviceCommand(action.toString())
                transitionTo(ServiceState.SPEAKING)
                pushEvent(mapOf("state" to "PLAYING", "transcript" to text, "response" to result.reply))
                overlayManager.show(OverlayManager.State.PLAYING, result.reply.take(40))
                ttsEngine.speak(result.reply)
            }
            override fun onActionReceived(payload: String) {
                mainHandler.post {
                    if (serviceDestroyed) return@post
                    cancelBackendFallback()
                    dispatchDeviceCommand(payload)
                }
            }
        })
        audioStreamer?.setPowerManager(getSystemService(Context.POWER_SERVICE) as? PowerManager)
        audioStreamer?.connect(target)
    }

    private fun isScreenAwareRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf("tap", "click", "type", "screen", "notification", "reply to")
            .any { normalized.contains(it) }
    }

    private fun publishCurrentState() {
        pushEvent(mapOf(
            "stateSync" to "true",
            "state" to when (serviceState) {
                ServiceState.IDLE -> "IDLE"
                ServiceState.LISTENING -> "LISTENING"
                ServiceState.PROCESSING -> "PROCESSING"
                ServiceState.SPEAKING -> "SPEAKING"
            },
            "wsConnected" to (audioStreamer?.isConnected() == true).toString()
        ))
    }

    @SuppressLint("MissingPermission")
    private fun startLocalTtsBargeInMonitor() {
        if (localBargeInJob?.isActive == true || serviceDestroyed) return
        localBargeInJob = scope.launch {
            var recorder: AudioRecord? = null
            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null
            try {
                val frameSize = 512
                val minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = createAudioRecord(SAMPLE_RATE, maxOf(minimum, frameSize * 4))
                    ?: return@launch
                val session = recorder.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(session)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(session)?.apply { enabled = true }
                }
                recorder.startRecording()
                sileroVad?.resetStates()
                val frame = ShortArray(frameSize)
                var speechFrames = 0
                while (isActive && serviceState == ServiceState.SPEAKING) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = frame[i].toDouble() / Short.MAX_VALUE
                        sum += sample * sample
                    }
                    val rms = kotlin.math.sqrt(sum / read).toFloat()
                    val score = sileroVad?.isSpeech(frame, SAMPLE_RATE) ?: -1f
                    if (score >= 0.38f || rms >= 0.018f) speechFrames++ else speechFrames = 0
                    if (speechFrames >= 3) {
                        try { recorder.stop() } catch (_: Throwable) {}
                        mainHandler.post {
                            if (serviceState == ServiceState.SPEAKING) {
                                beginActionTurn()
                                ttsEngine.stop()
                                audioStreamer?.interruptActiveTurn()
                                transitionTo(ServiceState.LISTENING)
                                overlayManager.show(OverlayManager.State.LISTENING)
                                pushEvent(mapOf("state" to "LISTENING"))
                                requestAudioFocus()
                                if (audioStreamer?.isConnected() == true) {
                                    captureAndSendAudio()
                                } else {
                                    sttEngine.startListening()
                                }
                            }
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (serviceState == ServiceState.SPEAKING) {
                    Log.w(TAG, "Local TTS barge-in monitor failed: ${e.message}")
                }
            } finally {
                try { noiseSuppressor?.release() } catch (_: Throwable) {}
                try { echoCanceler?.release() } catch (_: Throwable) {}
                try {
                    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder?.stop()
                } catch (_: Throwable) {}
                try { recorder?.release() } catch (_: Throwable) {}
                localBargeInJob = null
            }
        }
    }

    private fun dispatchDeviceCommand(payload: String, attemptsLeft: Int = 8) {
        dispatchDeviceCommand(payload, attemptsLeft, confirmed = false)
    }

    private fun dispatchDeviceCommand(payload: String, attemptsLeft: Int, confirmed: Boolean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { dispatchDeviceCommand(payload, attemptsLeft, confirmed) }
            return
        }
        val parsedPayload = try {
            JSONObject(payload)
        } catch (_: Exception) {
            Log.w(TAG, "Rejecting malformed device operation")
            return
        }
        if (!ActionPayloadValidator.isValidCommandEnvelope(
                parsedPayload,
                ExtendedOperations.EXTENDED_SETTINGS_ACTIONS.keys
            )
        ) {
            Log.w(TAG, "Rejecting device operation with invalid schema")
            return
        }
        if (!confirmed && requiresVoiceConfirmation(payload)) {
            requestVoiceConfirmation(payload)
            return
        }

        val operator = DeviceOperatorService.instance
        if (operator == null) {
            if (attemptsLeft > 0 && !serviceDestroyed) {
                Log.w(TAG, "Accessibility service not ready; retrying device operation")
                backendResponseHandler.postDelayed({
                    dispatchDeviceCommand(payload, attemptsLeft - 1, confirmed)
                }, 250L)
            } else {
                Log.e(TAG, "Dropping device operation: accessibility service unavailable")
            }
            return
        }

        val signature = actionSignature(payload)
        synchronized(dispatchedActionLock) {
            if (signature.isNotBlank() && signature in dispatchedActionSignatures) {
                Log.d(TAG, "Skipping duplicate device operation: $signature")
                return
            }
            if (signature.isNotBlank()) dispatchedActionSignatures.add(signature)
        }

        Log.i(TAG, "Dispatching device operation")
        operator.executeCommand(payload, confirmed)
    }

    private fun requiresVoiceConfirmation(payload: String): Boolean {
        return try {
            ActionPayloadValidator.requiresVoiceConfirmation(JSONObject(payload))
        } catch (_: Exception) {
            false
        }
    }

    private fun requestVoiceConfirmation(payload: String) {
        if (pendingSensitiveAction != null) return
        pendingSensitiveAction = payload
        mainHandler.post {
            if (serviceDestroyed || pendingSensitiveAction != payload) return@post
            closeLiveInputForLocalResponse()
            awaitingCommandAfterAck = true
            transitionTo(ServiceState.SPEAKING)
            val prompt = "This is a sensitive action. Say yes to confirm, or no to cancel."
            pushEvent(mapOf("state" to "PLAYING", "response" to prompt))
            overlayManager.show(OverlayManager.State.PLAYING, prompt)
            ttsEngine.speak(prompt)
        }
    }

    private fun handlePendingConfirmation(transcript: String): Boolean {
        val payload = pendingSensitiveAction ?: return false
        val answer = transcript.trim().lowercase()
        closeLiveInputForLocalResponse()
        val affirmative = answer.matches(Regex("^(yes|yeah|yep|confirm|do it|proceed)[.! ]*$"))
        val negative = answer.matches(Regex("^(no|nope|cancel|stop|don't)[.! ]*$"))

        if (affirmative) {
            pendingSensitiveAction = null
            transitionTo(ServiceState.PROCESSING)
            dispatchDeviceCommand(payload, attemptsLeft = 8, confirmed = true)
            val response = "Confirmed."
            transitionTo(ServiceState.SPEAKING)
            pushEvent(mapOf("state" to "PLAYING", "response" to response))
            ttsEngine.speak(response)
        } else if (negative) {
            pendingSensitiveAction = null
            transitionTo(ServiceState.SPEAKING)
            val response = "Canceled."
            pushEvent(mapOf("state" to "PLAYING", "response" to response))
            ttsEngine.speak(response)
        } else {
            awaitingCommandAfterAck = true
            transitionTo(ServiceState.SPEAKING)
            ttsEngine.speak("Please say yes to confirm, or no to cancel.")
        }
        return true
    }

    private fun closeLiveInputForLocalResponse() {
        if (captureJob?.isActive == true && audioStreamer?.isConnected() == true) {
            audioStreamer?.sendEndOfSpeech()
        }
        audioStreamer?.suppressCurrentTurnOutput()
        stopCaptureAudio()
        ttsEngine.stop()
    }

    private fun beginActionTurn() {
        synchronized(dispatchedActionLock) {
            dispatchedActionSignatures.clear()
            fastPathSignaturesThisTurn.clear()
        }
        lastBackendTranscript = ""
        synchronized(backendResponseLock) { lastBackendResponse = "" }
    }

    private fun appendBackendResponse(text: String) {
        synchronized(backendResponseLock) {
            lastBackendResponse += text
        }
    }

    private fun currentBackendResponse(): String =
        synchronized(backendResponseLock) { lastBackendResponse }

    private fun actionSignature(payload: String): String {
        return try {
            val json = JSONObject(payload)
            val params = JSONObject().apply {
                json.optJSONObject("params")?.let { nested ->
                    val keys = nested.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, nested.get(key))
                    }

                }
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in setOf("params", "type", "reply", "voiceConfirmed")) {
                        if (!has(key)) put(key, json.get(key))
                    }
                }
            }
            val action = json.optString("action")
            when (action) {
                "flashlightOn", "flashlightOff" -> "flashlight:${action == "flashlightOn"}"
                "controlHardware" -> {
                    val device = params.optString("device").lowercase()
                    val state = params.optString("state").lowercase()
                    when (device) {
                        "flashlight", "wifi", "bluetooth", "airplane" ->
                            if (state in setOf("on", "off")) "$device:${state == "on"}" else "$device:$state"
                        "volume" -> "volume:${if (state in setOf("increase", "up")) "up" else if (state in setOf("decrease", "down")) "down" else state}"
                        else -> "$device:$state"
                    }
                }
                "toggleWifi" -> "wifi:${params.optBoolean("enabled", true)}"
                "toggleBluetooth" -> "bluetooth:${params.optBoolean("enabled", true)}"
                "volumeUp" -> "volume:up"
                "volumeDown" -> "volume:down"
                "mute" -> "volume:mute"
                "globalBack" -> "global:back"
                "globalHome" -> "global:home"
                else -> "$action:${params}"
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun applyBackendWs(url: String) {
        val trimmed = url.trim()
        Log.i(TAG, "Backend switch requested")
        val apiKey = prefs.getString("gemini_api_key", "")?.trim().let { if (it.isNullOrBlank()) DEFAULT_GEMINI_KEY else it }
        if (trimmed.equals("offline", ignoreCase = true)) {
            backendWsUrl = null
            audioStreamer?.stopAll()
            audioStreamer = null
            pushEvent(mapOf("state" to "DISCONNECTED", "wsConnected" to "false"))
            Log.i(TAG, "Backend: offline local engine")
            return
        }

        val targetUrl = if (apiKey.isNotBlank() && (trimmed.isBlank() || trimmed.contains("googleapis.com"))) {
            "$DEFAULT_BACKEND_WS?key=$apiKey"
        } else if (trimmed.isNotBlank()) {
            if (trimmed.contains("googleapis.com")) {
                if (apiKey.isNotBlank()) {
                    if (trimmed.contains("key=")) trimmed else "$trimmed?key=$apiKey"
                } else {
                    DEFAULT_BACKEND_WS
                }
            } else {
                trimmed
            }
        } else if (apiKey.isNotBlank()) {
            "$DEFAULT_BACKEND_WS?key=$apiKey"
        } else {
            DEFAULT_BACKEND_WS
        }

        if (targetUrl == backendWsUrl && (audioStreamer?.isConnected() == true || audioStreamer?.isConnecting() == true)) {
            Log.d(TAG, "Backend already active: $targetUrl")
            return
        }

        backendWsUrl = targetUrl
        if (audioStreamer == null) {
            configureBackendStreamer()
        } else {
            audioStreamer?.connect(targetUrl)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val saved = prefs.getString(KEY_BACKEND_WS, DEFAULT_BACKEND_WS)?.trim() ?: DEFAULT_BACKEND_WS
            val apiKey = prefs.getString("gemini_api_key", "")?.trim().let { if (it.isNullOrBlank()) DEFAULT_GEMINI_KEY else it }
            val expectedTarget = if (apiKey.isNotBlank() && (saved.isBlank() || saved.contains("googleapis.com"))) {
                "$DEFAULT_BACKEND_WS?key=$apiKey"
            } else if (saved.isNotBlank()) {
                if (saved.contains("googleapis.com") && apiKey.isNotBlank() && !saved.contains("key=")) {
                    "$saved?key=$apiKey"
                } else saved
            } else {
                DEFAULT_BACKEND_WS
            }

            if (expectedTarget != backendWsUrl) {
                applyBackendWs(saved)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend re-read failed: ${e.message}")
        }

        if (intent?.action == "TRIGGER_MANUAL") {
            manualTrigger()
            return START_STICKY
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hey Bro – Listening")
            .setContentText("Say \"Hey Bro\" to activate · Engine: $activeEngineName")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setDefaults(0)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            startWakeWordLoop()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed = true
        mainHandler.removeCallbacks(startWakeWordRunnable)
        cancelStateWatchdog()
        cancelSttRetry()
        stopCaptureAudio()
        localBargeInJob?.cancel()
        localBargeInJob = null
        cancelBackendFallback()
        backendResponseHandler.removeCallbacksAndMessages(null)
        try { audioStreamer?.stopAll() } catch (_: Exception) {}
        audioStreamer = null
        try { audioJob?.cancel() } catch (_: Exception) {}
        audioJob = null
        releaseWakeRecorder()
        try { wakeWordManager?.release() } catch (_: Exception) {}
        try { sileroVad?.close() } catch (_: Exception) {}
        try { sttEngine.destroy() } catch (_: Exception) {}
        try { ttsEngine.destroy() } catch (_: Exception) {}
        try { overlayManager.destroy() } catch (_: Exception) {}
        try { serviceJob.cancel() } catch (_: Exception) {}
        try {
            if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
        } catch (_: Exception) {}
        cpuWakeLock = null
        abandonAudioFocus()
        activeInstance = null
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed; restarting foreground service for background reliability")
        val restartIntent = Intent(applicationContext, CoreVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    //  LocalSttEngine.Listener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSpeechBegin() {
        Log.d(TAG, "User began speaking")
        lastHandledSttTranscript = ""
        sttFailureCount = 0
        sttRetryInFlight = false
        abandonAudioFocus()
    }

    override fun onResult(transcript: String) {
        val transcriptKey = transcript.trim().lowercase().replace(Regex("\\s+"), " ")
        if (transcriptKey.isBlank() || transcriptKey == lastHandledSttTranscript) return
        lastHandledSttTranscript = transcriptKey
        sttFailureCount = 0
        sttRetryInFlight = false
        abandonAudioFocus()

        Log.i(TAG, "STT result received (${transcript.length} characters)")
        if (handlePendingConfirmation(transcript)) return
        transitionTo(ServiceState.PROCESSING)
        pushEvent(mapOf("state" to "PROCESSING", "transcript" to transcript))
        overlayManager.show(OverlayManager.State.PROCESSING)

        scope.launch(Dispatchers.Default) {
            var result = LocalCommandProcessor.process(transcript)
            if (isUnresolved(result)) {
                LocalCommandProcessor.interpretWithGemini(transcript)?.let { geminiResult ->
                    result = geminiResult
                    Log.i(TAG, "Used Gemini fallback for transcript: \"$transcript\"")
                }
            }

            result.action?.let { actionJson ->
                val actionStr = actionJson.optString("action")
                Log.d(TAG, "Executing action: $actionStr")
                VoiceLearningStore.learn(transcript, actionStr)

                if (requiresVoiceConfirmation(actionJson.toString())) {
                    requestVoiceConfirmation(actionJson.toString())
                    return@launch
                }

                if (actionStr == "sleep") {
                    resetToIdle()
                    pushEvent(mapOf("state" to "IDLE"))
                    overlayManager.hideDelayed(500)
                    ttsEngine.speak(result.reply)
                    return@launch
                }

                dispatchDeviceCommand(actionJson.toString())
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                transitionTo(ServiceState.SPEAKING)
                pushEvent(mapOf("state" to "PLAYING", "response" to result.reply))
                overlayManager.show(OverlayManager.State.PLAYING, result.reply.take(40))
                ttsEngine.speak(result.reply)
            }
        }
    }

    override fun onPartialResult(transcript: String) {
        if (serviceState != ServiceState.LISTENING) return

        // Upgrade B2: Parallel Local Fast-Path (<50ms execution on first transcript chunk)
        val fastResult = LocalCommandProcessor.checkFastPath(transcript, partial = true)
        if (fastResult != null && fastResult.action != null) {
            Log.i(TAG, "High-confidence parallel fast-path triggered in <50ms: \"$transcript\"")
            try { sttEngine.stopListening() } catch (_: Exception) {}
            onResult(transcript)
        }
    }

    private fun isUnresolved(result: LocalCommandProcessor.Result): Boolean {
        return result.action == null && (
            result.reply.startsWith("Sorry, I didn't understand") ||
                result.reply.contains("ready to help", ignoreCase = true)
            )
    }

    override fun onError(errorCode: Int) {
        if (serviceState != ServiceState.LISTENING) {
            Log.d(TAG, "Ignoring stale STT error $errorCode while service is $serviceState")
            return
        }

        lastFailedListenAtMs = SystemClock.elapsedRealtime()
        sttFailureCount++

        val msg = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH       -> "I didn't catch that. Try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
            SpeechRecognizer.ERROR_AUDIO          -> "Audio error. Please try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
            else                                  -> null
        }

        Log.w(TAG, "STT error $errorCode (count=$sttFailureCount)${if (msg != null) ": $msg" else " (silent)"}")

        val retryDelays = longArrayOf(600L)
        val shouldRetry = errorCode == SpeechRecognizer.ERROR_AUDIO && sttFailureCount <= retryDelays.size

        if (shouldRetry) {
            val idx = (sttFailureCount - 1).coerceAtLeast(0).coerceAtMost(retryDelays.size - 1)
            val delayMs = retryDelays.getOrNull(idx) ?: 3000L
            Log.d(TAG, "Transient STT error; scheduling retry #$sttFailureCount (delay=${delayMs}ms)")
            cancelSttRetry()
            val runnable = Runnable {
                try {
                    if (serviceDestroyed || serviceState != ServiceState.LISTENING) {
                        Log.d(TAG, "STT retry aborted because service is $serviceState")
                        return@Runnable
                    }

                    sttRetryInFlight = true
                    requestAudioFocus()

                    try {
                        sttEngine.startListening()
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry startListening failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Retry startListening failed outer: ${e.message}")
                }
            }
            sttRetryRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
            return
        }

        mainHandler.post {
            cancelSttRetry()
            abandonAudioFocus()

            if (msg != null) {
                transitionTo(ServiceState.SPEAKING)
                overlayManager.show(OverlayManager.State.PLAYING, msg)
                pushEvent(mapOf("state" to "PLAYING", "response" to msg))
                ttsEngine.speak(msg)
            } else {
                resetToIdle()
                pushEvent(mapOf("state" to "IDLE"))
                overlayManager.hideDelayed(800)
            }
            sttFailureCount = 0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LocalTtsEngine.Listener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSpeechStart() {
        Log.d(TAG, "TTS speaking")
        if (serviceState == ServiceState.SPEAKING && captureJob?.isActive != true) {
            startLocalTtsBargeInMonitor()
        }
    }

    override fun onSpeechDone() {
        Log.d(TAG, "TTS done")
        scope.launch(Dispatchers.Main) {
            if (awaitingCommandAfterAck) {
                awaitingCommandAfterAck = false
                transitionTo(ServiceState.LISTENING)
                overlayManager.show(OverlayManager.State.LISTENING)
                pushEvent(mapOf("state" to "LISTENING"))
                updateNotification("I'm listening…")

                val postTtsDelay = prefs.getLong("stt_post_tts_ms", 200L).coerceIn(0L, 600L)
                mainHandler.postDelayed({
                    if (audioStreamer?.isConnected() == true) {
                        audioStreamer?.sendWakeWordTriggered()
                        requestAudioFocus()
                        captureAndSendAudio()
                    } else {
                        try {
                            val gotFocus = requestAudioFocus()
                            if (!gotFocus) {
                                mainHandler.postDelayed({
                                    try {
                                        requestAudioFocus()
                                        sttEngine.startListeningPostTts(250L)
                                    } catch (e: Exception) { Log.w(TAG, "STT start failed after ack (delayed): ${e.message}") }
                                }, 700)
                            } else {
                                sttEngine.startListeningPostTts(250L)
                            }
                        } catch (e: Exception) { Log.w(TAG, "STT start failed after ack: ${e.message}") }
                    }
                }, postTtsDelay)
                
            } else {
                resetToIdle()
                pushEvent(mapOf("state" to "IDLE"))
                overlayManager.hideDelayed(800)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Initialization
    // ─────────────────────────────────────────────────────────────────────────

    private fun initWakeWordEngine() {
        try {
            wakeWordManager = WakeWordManager(applicationContext)
            wakeWordManager?.initialize()

            val activeWakeEngine = wakeWordManager?.activeEngine
            if (activeWakeEngine is HeyBroPhraseWakeEngine) {
                activeWakeEngine.setWakeCallback { directCommand ->
                    if (serviceState == ServiceState.IDLE) {
                        onWakeWordDetected(directCommand)
                    }
                }
            }

            activeEngineName = wakeWordManager?.getActiveEngineName() ?: "None"
            Log.i(TAG, "Wake engine: $activeEngineName")
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordManager init failed: ${e.message}")
        }
    }

    private fun initVAD() {
        try {
            sileroVad = SileroVad(applicationContext)
            Log.i(TAG, "Silero VAD initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Silero VAD unavailable: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Android persists channel sound choices. Remove channels created by
            // older builds so a previously selected notification sound cannot
            // return after reinstalling or restarting the service.
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID_V1)
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID_V2)
            notificationManager.deleteNotificationChannel(CHANNEL_ID)

            val channel = NotificationChannel(
                CHANNEL_ID, "Hey Bro Service", NotificationManager.IMPORTANCE_MIN
            ).also {
                it.description = "Foreground service for Hey Bro assistant"
                it.setSound(null, null)
                it.enableVibration(false)
                it.vibrationPattern = longArrayOf(0L)
                it.enableLights(false)
                it.setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ── Safe AudioRecord Creation ─────────────────────────────────────────────
    private fun createAudioRecord(sampleRate: Int, bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            try {
                val rec = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec
                } else {
                    try { rec.release() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wake word audio loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun releaseWakeRecorder(expected: AudioRecord? = null) {
        synchronized(audioRecordLock) {
            val recorder = audioRecord ?: return
            if (expected != null && recorder !== expected) return
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            if (audioRecord === recorder) {
                audioRecord = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWakeWordLoop() {
        if (audioJob?.isActive == true) return
        if (serviceDestroyed || serviceState != ServiceState.IDLE) return

        if (wakeWordManager?.activeEngine == null) {
            initWakeWordEngine()
        }

        audioJob = scope.launch {
            var recorder: AudioRecord? = null
            try {
                val frameLength = wakeWordManager?.getRequiredFrameLength() ?: 512
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, frameLength * 2)

                var createdRecorder: AudioRecord? = null
                for (attempt in 1..4) {
                    if (!isActive || serviceState != ServiceState.IDLE) return@launch
                    createdRecorder = createAudioRecord(SAMPLE_RATE, bufSize)
                    if (createdRecorder != null) break
                    delay(120L * attempt)
                }

                if (createdRecorder == null) {
                    Log.e(TAG, "Wake word loop: failed to initialize AudioRecord across audio sources")
                    return@launch
                }
                recorder = createdRecorder
                synchronized(audioRecordLock) {
                    audioRecord = createdRecorder
                    try {
                        createdRecorder.startRecording()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Wake word loop startRecording failed: ${e.message}")
                        return@launch
                    }
                }

                val buffer = ShortArray(frameLength)

                Log.i(TAG, "Continuous 16kHz AudioRecord wake loop active (OriginOS 6 / Dimensity 9400+ background mic)")

                while (isActive && serviceState == ServiceState.IDLE) {
                    val rec = synchronized(audioRecordLock) { audioRecord } ?: break
                    if (rec !== recorder) break // Ownership changed
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                    
                    val read = rec.read(buffer, 0, frameLength)
                    if (read < 0) {
                        Log.w(TAG, "Wake loop AudioRecord read error: $read")
                        break
                    }
                    if (read != frameLength) continue

                    var peak = 0
                    for (sample in buffer) peak = maxOf(peak, kotlin.math.abs(sample.toInt()))
                    if (peak >= 5_000) {
                        // Prewarm on speech energy; HeyBroPhraseWakeEngine performs the sole wake VAD inference.
                        audioStreamer?.prewarm(backendWsUrl ?: "")
                    }

                    if (wakeWordManager?.process(buffer) == true) {
                        Log.i(TAG, "Wake word detected by ${wakeWordManager?.getActiveEngineName()}")
                        onWakeWordDetected()
                        break
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Wake word loop error: ${e.message}")
            } finally {
                releaseWakeRecorder(recorder)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wake word / state transitions
    // ─────────────────────────────────────────────────────────────────────────

    private fun onWakeWordDetected(directCommand: String? = null) {
        val nowMs = SystemClock.elapsedRealtime()

        // Siri-like: If assistant is currently speaking, user saying "Hey Bro" interrupts immediately!
        if (serviceState == ServiceState.SPEAKING || serviceState == ServiceState.PROCESSING) {
            Log.i(TAG, "Interrupting active response on wake phrase")
            ttsEngine.stop()
            audioStreamer?.interruptActiveTurn()
            resetToIdle()
            mainHandler.postDelayed({
                onWakeWordDetected(directCommand)
            }, 100L)
            return
        }

        if (serviceState != ServiceState.IDLE) return
        if (nowMs - lastWakeAtMs < 750L) {
            Log.d(TAG, "Wake cooldown active; ignoring extra trigger")
            return
        }
        if (nowMs - lastFailedListenAtMs < 500L) {
            Log.d(TAG, "Recent STT failure cooldown active; not re-triggering wake detection")
            return
        }

        lastWakeAtMs = nowMs
        beginActionTurn()
        lastHandledSttTranscript = ""

        try {
            audioJob?.cancel()
            audioJob = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel audioJob: ${e.message}")
        }
        releaseWakeRecorder()

        playFeedback()

        // OriginOS 6: Light up the display if screen is currently off
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val screenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HeyBro:ScreenWake"
                )
                screenLock.acquire(5000L) // 5s illumination
                Log.i(TAG, "OriginOS 6: Screen turned on via ACQUIRE_CAUSES_WAKEUP")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screen wake failed: ${e.message}")
        }

        // Android 15: Launch MainActivity over lockscreen using PendingIntent with BACKGROUND_ACTIVITY_START_ALLOWED
        try {
            val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = Intent.ACTION_ASSIST
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
            } else {
                ActivityOptions.makeBasic()
            }
            val pi = PendingIntent.getActivity(
                applicationContext,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send(applicationContext, 0, null, null, null, null, options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch lockscreen overlay via PendingIntent: ${e.message}")
        }

        // Reset wake engine internal state without destroying it!
        (wakeWordManager?.activeEngine as? HeyBroPhraseWakeEngine)?.resetState()

        // Fast-path: if the user spoken a complete command along with the wake phrase
        // (e.g. "Hey bro turn on flashlight"), execute it immediately without interrupting
        // them with a spoken greeting!
        if (!directCommand.isNullOrBlank()) {
            Log.i(TAG, "One-shot wake word with a direct command")
            transitionTo(ServiceState.PROCESSING)
            overlayManager.show(OverlayManager.State.PROCESSING, directCommand.take(40))
            pushEvent(mapOf("state" to "PROCESSING", "transcript" to directCommand, "activeEngine" to activeEngineName))
            updateNotification("Processing: $directCommand")
            onResult(directCommand)
            return
        }

        transitionTo(ServiceState.LISTENING)
        overlayManager.show(OverlayManager.State.LISTENING)
        pushEvent(mapOf("state" to "LISTENING", "activeEngine" to activeEngineName))
        updateNotification("I'm listening…")

        // Siri-like: Do not speak an interrupting greeting ("Hi Sandip, what can I do for you?").
        // Speaking a greeting causes acoustic echo that blocks the mic from hearing the user!
        // Instead, the subtle haptic feedback + Siri glowing orb on screen indicates readiness,
        // and we immediately start recording the user's speech!
        requestAudioFocus()
        if (audioStreamer?.isConnected() == true || audioStreamer?.isConnecting() == true) {
            audioStreamer?.sendWakeWordTriggered()
            captureAndSendAudio()
        } else {
            sttEngine.startListening()
        }
    }

    fun manualTrigger() {
        if (serviceState == ServiceState.IDLE) {
            Log.d(TAG, "Manual push-to-talk trigger")
            onWakeWordDetected()
        } else if (serviceState == ServiceState.LISTENING) {
            Log.d(TAG, "Manual push-to-talk restart")
            stopCaptureAudio()
            if (audioStreamer?.isConnected() == true) {
                audioStreamer?.sendWakeWordTriggered()
                requestAudioFocus()
                captureAndSendAudio()
            } else {
                requestAudioFocus()
                sttEngine.startListening()
            }
        } else if (serviceState == ServiceState.SPEAKING || serviceState == ServiceState.PROCESSING) {
            ttsEngine.stop()
            audioStreamer?.interruptActiveTurn()
            resetToIdle()
            mainHandler.postDelayed({
                onWakeWordDetected()
            }, 100L)
        }
    }

    private fun resetToIdle() {
        cancelStateWatchdog()
        cancelSttRetry()
        localBargeInJob?.cancel()
        localBargeInJob = null
        transitionTo(ServiceState.IDLE)
        sileroVad?.resetStates()
        stopCaptureAudio()
        sttEngine.stopListening()
        try {
            audioJob?.cancel()
            audioJob = null
        } catch (_: Exception) {}
        releaseWakeRecorder()

        (wakeWordManager?.activeEngine as? HeyBroPhraseWakeEngine)?.resetState()

        mainHandler.removeCallbacks(startWakeWordRunnable)
        mainHandler.postDelayed(startWakeWordRunnable, 200L)

        updateNotification("Say \"Hey Bro\" to activate")
    }

    private fun scheduleWakeReinit(attemptsLeft: Int = 4) {
        if (serviceDestroyed || serviceState != ServiceState.IDLE) return
        if (wakeWordManager?.activeEngine != null) return
        if (attemptsLeft <= 0) {
            try {
                initWakeWordEngine()
            } catch (e: Exception) {
                Log.w(TAG, "WakeWordManager final re-init failed: ${e.message}")
            }
            return
        }

        if (sttRetryInFlight || sttFailureCount > 0) {
            Log.d(TAG, "Deferring WakeWordManager re-init due to STT retry state")
            mainHandler.postDelayed({ scheduleWakeReinit(attemptsLeft - 1) }, 1000)
            return
        }

        try {
            initWakeWordEngine()
        } catch (e: Exception) {
            Log.w(TAG, "WakeWordManager re-init failed: ${e.message}")
            mainHandler.postDelayed({ scheduleWakeReinit(attemptsLeft - 1) }, 1000)
        }
    }

    @Synchronized
    private fun transitionTo(newState: ServiceState) {
        Log.d(TAG, "State: $serviceState → $newState")
        serviceState = newState
        when (newState) {
            ServiceState.IDLE -> cancelStateWatchdog()
            ServiceState.LISTENING -> scheduleStateWatchdog(20_000L, ServiceState.LISTENING)
            ServiceState.PROCESSING -> scheduleStateWatchdog(30_000L, ServiceState.PROCESSING)
            ServiceState.SPEAKING -> scheduleStateWatchdog(45_000L, ServiceState.SPEAKING)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feedback
    // ─────────────────────────────────────────────────────────────────────────

    private fun playFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vm.defaultVibrator
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(60)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration skipped: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureAndSendAudio() {
        stopCaptureAudio()
        captureJob = scope.launch {
            var recorder: AudioRecord? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var echoCanceler: AcousticEchoCanceler? = null
            var gainControl: AutomaticGainControl? = null

            try {
                try { sttEngine.stopListening() } catch (_: Exception) {}

                val sampleRate = SAMPLE_RATE
                val frameSize = 512
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = maxOf(minBuf, frameSize * 4)

                var createdRecorder: AudioRecord? = null
                for (attempt in 1..3) {
                    if (!isActive || serviceState != ServiceState.LISTENING) return@launch
                    createdRecorder = createAudioRecord(sampleRate, bufSize)
                    if (createdRecorder != null) break
                    delay(80L * attempt)
                }

                if (createdRecorder == null) {
                    Log.e(TAG, "Capture audio: failed to initialize AudioRecord; falling back to local STT")
                    if (!serviceDestroyed && serviceState == ServiceState.LISTENING) {
                        requestAudioFocus()
                        try { sttEngine.startListening() } catch (_: Throwable) {}
                    }
                    return@launch
                }
                recorder = createdRecorder
                captureAudioRecord = createdRecorder
                sileroVad?.resetStates()

                try {
                    val sessionId = recorder.audioSessionId
                    if (NoiseSuppressor.isAvailable()) {
                        try { noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true } } catch (_: Throwable) {}
                    }
                    if (AcousticEchoCanceler.isAvailable()) {
                        try { echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } } catch (_: Throwable) {}
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        try { gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true } } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Audio effects init: ${e.message}")
                }

                try {
                    recorder.startRecording()
                } catch (e: Throwable) {
                    Log.e(TAG, "Capture audio startRecording failed: ${e.message}")
                    if (!serviceDestroyed && serviceState == ServiceState.LISTENING) {
                        requestAudioFocus()
                        try { sttEngine.startListening() } catch (_: Throwable) {}
                    }
                    return@launch
                }

                val shortBuf = ShortArray(frameSize)
                var speechDetected = false
                var silenceFrames = 0
                val maxSilenceFrames = 12 // 384ms of sustained silence
                var speechFramesCount = 0
                val minSpeechFrames = 3
                val noSpeechTimeoutMs = 6000L
                val maxDurationMs = 15000L
                val startMs = SystemClock.elapsedRealtime()

                // Keep 256ms of audio so the first consonants survive VAD onset latency.
                val preRollBuffer = ArrayDeque<ByteArray>(8)

                // Dynamic Noise Floor Calibration with exponential moving average
                var noiseFloorRms = 0.002f

                // DC-blocking 1-pole high-pass filter (~80Hz cutoff) to eliminate DC offset and sub-bass table rumble
                var hpPrevX = 0f
                var hpPrevY = 0f
                val hpAlpha = 0.985f

                Log.d(TAG, "Dynamic crystal-clear capture engine started")

                val connectGraceMs = 500L
                while (isActive && SystemClock.elapsedRealtime() - startMs < maxDurationMs) {
                    if (audioStreamer?.isConnected() != true) {
                        if (audioStreamer?.isConnecting() == true && (SystemClock.elapsedRealtime() - startMs) < connectGraceMs) {
                            val r = recorder.read(shortBuf, 0, frameSize)
                            if (r > 0) {
                                val bytes = ByteArray(r * 2)
                                var bi = 0
                                for (i in 0 until r) {
                                    val s = shortBuf[i]
                                    bytes[bi++] = (s.toInt() and 0xFF).toByte()
                                    bytes[bi++] = ((s.toInt() shr 8) and 0xFF).toByte()
                                }
                                if (preRollBuffer.size >= 12) preRollBuffer.removeFirst()
                                preRollBuffer.addLast(bytes)
                            }
                            continue
                        } else {
                            Log.w(TAG, "Socket unavailable during capture; instant fallback to on-device STT")
                            requestAudioFocus()
                            try { sttEngine.startListening() } catch (_: Exception) {}
                            return@launch
                        }
                    }

                    val r = recorder.read(shortBuf, 0, frameSize)
                    if (r <= 0) continue

                    // 1. DC-offset & rumble removal + peak measurement
                    var maxSample = 0
                    for (i in 0 until r) {
                        val x = shortBuf[i].toFloat()
                        val y = x - hpPrevX + hpAlpha * hpPrevY
                        hpPrevX = x
                        hpPrevY = y
                        val filtered = y.coerceIn(-32768f, 32767f).toInt().toShort()
                        shortBuf[i] = filtered
                        val abs = kotlin.math.abs(filtered.toInt())
                        if (abs > maxSample) maxSample = abs
                    }

                    // Adaptive soft pre-amplification for low microphone input
                    val softGain = when {
                        maxSample in 1..3500 -> 2.0f
                        maxSample in 3501..7500 -> 1.4f
                        maxSample in 7501..12000 -> 1.2f
                        else -> 1.0f
                    }
                    if (softGain > 1.0f) {
                        for (i in 0 until r) {
                            val scaled = (shortBuf[i] * softGain).toInt().coerceIn(-32768, 32767).toShort()
                            shortBuf[i] = scaled
                        }
                    }

                    // 2. Calculate Frame RMS
                    var sum = 0.0
                    for (i in 0 until r) {
                        val norm = shortBuf[i].toDouble() / Short.MAX_VALUE
                        sum += norm * norm
                    }
                    val currentRms = kotlin.math.sqrt(sum / r.coerceAtLeast(1)).toFloat()

                    // 2. Query Silero VAD (with smoothed hysteresis)
                    val vadScore = try { sileroVad?.isSpeech(shortBuf, sampleRate) ?: -1f } catch (_: Exception) { -1f }
                    val vadSmoothed = try { sileroVad?.smoothProbability(vadScore, 0.32f) ?: false } catch (_: Exception) { false }

                    // Adapt noise floor only during verified non-speech frames
                    if (!speechDetected && vadScore in 0f..0.20f) {
                        noiseFloorRms = (noiseFloorRms * 0.95f) + (currentRms * 0.05f)
                    }

                    // Adaptive thresholds based on measured noise floor
                    val activeSpeechThreshold = (noiseFloorRms * 2.0f).coerceIn(0.004f, 0.025f)
                    val silenceThreshold = (noiseFloorRms * 1.3f).coerceIn(0.0025f, 0.015f)

                    val isSpeechFrame = vadSmoothed || (vadScore > 0.32f) || (currentRms > activeSpeechThreshold)

                    // 3. Convert frame to PCM byte chunk
                    val bytes = ByteArray(r * 2)
                    var bi = 0
                    for (i in 0 until r) {
                        val s = shortBuf[i]
                        bytes[bi++] = (s.toInt() and 0xFF).toByte()
                        bytes[bi++] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }

                    if (!speechDetected) {
                        if (preRollBuffer.size >= 8) {
                            preRollBuffer.removeFirst()
                        }
                        preRollBuffer.addLast(bytes)

                        if (isSpeechFrame) {
                            val bargeIn = serviceState == ServiceState.PROCESSING || serviceState == ServiceState.SPEAKING
                            if (bargeIn) {
                                Log.i(TAG, "User speech detected during model response; barging in")
                                beginActionTurn()
                                ttsEngine.stop()
                                audioStreamer?.interruptActiveTurn()
                                transitionTo(ServiceState.LISTENING)
                                overlayManager.show(OverlayManager.State.LISTENING)
                                pushEvent(mapOf("state" to "LISTENING"))
                            }
                            speechDetected = true
                            speechFramesCount = 1
                            silenceFrames = 0
                            Log.d(TAG, "Speech confirmed (RMS: $currentRms, NoiseFloor: $noiseFloorRms, VAD: $vadScore)")
                            while (preRollBuffer.isNotEmpty()) {
                                audioStreamer?.sendAudioChunk(preRollBuffer.removeFirst())
                            }
                        } else if (serviceState == ServiceState.LISTENING &&
                            SystemClock.elapsedRealtime() - startMs >= noSpeechTimeoutMs
                        ) {
                            Log.d(TAG, "Silence timeout (${noSpeechTimeoutMs}ms); resetting to idle")
                            resetToIdle()
                            pushEvent(mapOf("state" to "IDLE"))
                            break
                        }
                    } else {
                        // Speech already active: stream chunk in real-time
                        audioStreamer?.sendAudioChunk(bytes)

                        val isSilence = (!isSpeechFrame && currentRms < silenceThreshold) || (vadScore in 0f..0.20f && currentRms < silenceThreshold)
                        if (isSilence) {
                            silenceFrames++
                        } else {
                            speechFramesCount++
                            silenceFrames = 0
                        }

                        // End each user turn quickly, but keep the recorder open for barge-in.
                        if (speechFramesCount >= minSpeechFrames && silenceFrames >= maxSilenceFrames) {
                            Log.i(TAG, "Natural EOS detected ($silenceFrames frames quiet, $speechFramesCount spoken). Closing stream.")
                            audioStreamer?.sendEndOfSpeech()
                            transitionTo(ServiceState.PROCESSING)
                            overlayManager.show(OverlayManager.State.PROCESSING)
                            pushEvent(mapOf("state" to "PROCESSING"))
                            speechDetected = false
                            silenceFrames = 0
                            speechFramesCount = 0
                            preRollBuffer.clear()
                        }
                    }
                }

                if (speechDetected && serviceState == ServiceState.LISTENING) {
                    scheduleBackendFallback()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Dynamic capture error: ${e.message}")
                if (!serviceDestroyed && serviceState == ServiceState.LISTENING) {
                    requestAudioFocus()
                    try { sttEngine.startListening() } catch (_: Exception) {}
                }
            } finally {
                try { noiseSuppressor?.release() } catch (_: Throwable) {}
                try { echoCanceler?.release() } catch (_: Throwable) {}
                try { gainControl?.release() } catch (_: Throwable) {}
                val recToRelease = recorder
                if (recToRelease != null) {
                    try {
                        if (recToRelease.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            recToRelease.stop()
                        }
                    } catch (_: Throwable) {}
                    try { recToRelease.release() } catch (_: Throwable) {}
                }
                if (captureAudioRecord === recorder) {
                    captureAudioRecord = null
                }
                captureJob = null
            }
        }
    }

    private fun scheduleBackendFallback() {
        backendResponseHandler.removeCallbacks(backendFallbackRunnable)
        backendFallbackPending = true
        backendResponseHandler.postDelayed(backendFallbackRunnable, 2_500L)
    }

    private fun cancelBackendFallback() {
        backendFallbackPending = false
        backendResponseHandler.removeCallbacks(backendFallbackRunnable)
    }

    private fun pushEvent(data: Map<String, String>) {
        mainHandler.post {
            if (serviceDestroyed) return@post
            val payload = JSONObject(data).apply {
                put("sessionId", sessionId)
                put("sequenceId", eventSequence.incrementAndGet())
                put("serviceState", serviceState.name)
            }
            eventSink?.success(payload.toString())
        }
    }

    private fun updateNotification(text: String) {
        Log.d(TAG, "Service state notification: $text")
    }
}
