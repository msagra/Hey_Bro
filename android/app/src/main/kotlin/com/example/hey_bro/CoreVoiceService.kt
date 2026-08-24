package com.example.hey_bro

import android.annotation.SuppressLint
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
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        private const val DEFAULT_BACKEND_WS = "wss://hey-bro-1zxk.onrender.com/ws/audio"

        var activeEngineName: String = "None"
        var eventSink: EventChannel.EventSink? = null

        @Volatile private var activeInstance: CoreVoiceService? = null

        fun updateBackendWs(url: String) {
            activeInstance?.applyBackendWs(url)
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile private var serviceState = ServiceState.IDLE
    private var sttFailureCount = 0
    private var sttRetryInFlight = false

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
                val res = am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
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
    private var lastBackendTranscript = ""
    private val dispatchedActionLock = Any()
    private var lastDispatchedActionSignature = ""
    private var lastDispatchedActionAtMs = 0L
    @Volatile private var serviceDestroyed = false
    private val backendResponseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backendFallbackPending = false

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
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val PICOVOICE_ACCESS_KEY = "YOUR_PICOVOICE_ACCESS_KEY_HERE"
    private var lastWakeAtMs = 0L
    private var lastFailedListenAtMs = 0L

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed = false
        Log.d(TAG, "Service created")
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
    }

    private fun configureBackendStreamer() {
        val url = backendWsUrl ?: return
        audioStreamer = AudioStreamer(object : AudioStreamerListener {
            override fun onResponseComplete() {
                cancelBackendFallback()
                abandonAudioFocus()
                if (serviceState != ServiceState.IDLE) {
                    resetToIdle()
                    pushEvent(mapOf("state" to "IDLE"))
                    overlayManager.hideDelayed(300)
                }
            }
            override fun onConnectionStateChanged(connected: Boolean) {
                Log.i(TAG, "AudioStreamer connected=$connected")
                pushEvent(mapOf(
                    "state" to (if (connected) "CONNECTED" else "DISCONNECTED"),
                    "wsConnected" to (if (connected) "true" else "false")
                ))
                if (!connected && serviceState == ServiceState.LISTENING) {
                    // Cloud capture cannot finish while the socket is down. Move
                    // immediately to the local recognizer instead of leaving the
                    // UI stuck in LISTENING until the backend timeout expires.
                    backendFallbackPending = false
                    backendResponseHandler.removeCallbacks(backendFallbackRunnable)
                    captureJob?.cancel()
                    requestAudioFocus()
                    sttEngine.startListening()
                }
            }
            override fun onTranscriptReceived(text: String) {
                if (text.trim().matches(Regex("^[^\\p{L}\\p{N}]+$"))) {
                    Log.d(TAG, "Ignoring punctuation-only backend transcript: '$text'")
                    cancelBackendFallback()
                    resetToIdle()
                    pushEvent(mapOf("state" to "IDLE"))
                    return
                }
                cancelBackendFallback()
                lastBackendTranscript = text
                transitionTo(ServiceState.PROCESSING)
                pushEvent(mapOf("state" to "PROCESSING", "transcript" to text))
                overlayManager.show(OverlayManager.State.PROCESSING)

                // Keep device control functional when a deployed backend sends
                // TRANSCRIPT/SPOKEN_TEXT but omits its separate action message.
                // A matching backend action is deduplicated in dispatchDeviceCommand.
                scope.launch(Dispatchers.Default) {
                    var localResult = LocalCommandProcessor.process(text)
                    if (isUnresolved(localResult)) {
                        LocalCommandProcessor.interpretWithGemini(text)?.let { learnedResult ->
                            localResult = learnedResult
                            Log.i(TAG, "Gemini mapped cloud transcript: \"$text\"")
                        }
                    }
                    localResult.action?.let {
                        val actionName = it.optString("action")
                        VoiceLearningStore.learn(text, actionName)
                        dispatchDeviceCommand(it.toString())
                    }
                }
            }
            override fun onSpokenTextReceived(text: String) {
                transitionTo(ServiceState.SPEAKING)
                pushEvent(mapOf(
                    "state" to "PLAYING",
                    "transcript" to lastBackendTranscript,
                    "response" to text
                ))
                overlayManager.show(OverlayManager.State.PLAYING, text.take(40))
            }
            override fun onActionReceived(payload: String) {
                dispatchDeviceCommand(payload)
            }
        })
        audioStreamer?.connect(url)
    }

    private fun dispatchDeviceCommand(payload: String, attemptsLeft: Int = 8) {
        val operator = DeviceOperatorService.instance
        if (operator == null) {
            if (attemptsLeft > 0 && !serviceDestroyed) {
                Log.w(TAG, "Accessibility service not ready; retrying device operation")
                backendResponseHandler.postDelayed({
                    dispatchDeviceCommand(payload, attemptsLeft - 1)
                }, 250L)
            } else {
                Log.e(TAG, "Dropping device operation: accessibility service unavailable")
            }
            return
        }

        val signature = actionSignature(payload)
        val now = SystemClock.elapsedRealtime()
        synchronized(dispatchedActionLock) {
            if (signature.isNotBlank() &&
                signature == lastDispatchedActionSignature &&
                now - lastDispatchedActionAtMs < 5_000L
            ) {
                Log.d(TAG, "Skipping duplicate device operation: $signature")
                return
            }
            if (signature.isNotBlank()) {
                lastDispatchedActionSignature = signature
                lastDispatchedActionAtMs = now
            }
        }

        Log.i(TAG, "Dispatching device operation: $payload")
        operator.executeCommand(payload)
    }

    private fun actionSignature(payload: String): String {
        return try {
            val json = JSONObject(payload)
            val action = json.optString("action")
            val params = json.optJSONObject("params")
            when (action) {
                "flashlightOn", "flashlightOff" -> "flashlight:${action == "flashlightOn"}"
                "volumeUp" -> "volume:up"
                "volumeDown" -> "volume:down"
                "mute" -> "volume:mute"
                "globalBack" -> "global:back"
                "globalHome" -> "global:home"
                else -> "$action:${params?.toString() ?: json.toString()}"
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun applyBackendWs(url: String) {
        val trimmed = url.trim()
        Log.i(TAG, "Backend switch request: '$url'")
        if (trimmed.isEmpty()) {
            backendWsUrl = null
            audioStreamer?.stopAll()
            audioStreamer = null
            pushEvent(mapOf("state" to "DISCONNECTED", "wsConnected" to "false"))
            Log.i(TAG, "Backend: offline local engine")
            return
        }
        backendWsUrl = trimmed
        if (audioStreamer == null) {
            configureBackendStreamer()
        } else {
            audioStreamer?.connect(trimmed)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val saved = prefs.getString(KEY_BACKEND_WS, DEFAULT_BACKEND_WS)
            val current = backendWsUrl ?: ""
            if (saved != null && saved.trim() != current) {
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
        cancelBackendFallback()
        backendResponseHandler.removeCallbacksAndMessages(null)
        try { audioStreamer?.stopAll() } catch (_: Exception) {}
        audioStreamer = null
        try { audioJob?.cancel() } catch (_: Exception) {}
        releaseWakeRecorder()
        try { wakeWordManager?.release() } catch (_: Exception) {}
        try { sileroVad?.close() } catch (_: Exception) {}
        try { sttEngine.destroy() } catch (_: Exception) {}
        try { ttsEngine.destroy() } catch (_: Exception) {}
        try { overlayManager.destroy() } catch (_: Exception) {}
        try { serviceJob.cancel() } catch (_: Exception) {}
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
        sttFailureCount = 0
        sttRetryInFlight = false
        abandonAudioFocus()
    }

    override fun onResult(transcript: String) {
        sttFailureCount = 0
        sttRetryInFlight = false
        abandonAudioFocus()

        Log.i(TAG, "STT result: \"$transcript\"")
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

                if (actionStr == "sleep") {
                    resetToIdle()
                    pushEvent(mapOf("state" to "IDLE"))
                    overlayManager.hideDelayed(500)
                    ttsEngine.speak(result.reply)
                    return@launch
                }

                DeviceOperatorService.instance?.executeCommand(actionJson.toString())
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                transitionTo(ServiceState.SPEAKING)
                pushEvent(mapOf("state" to "PLAYING", "response" to result.reply))
                overlayManager.show(OverlayManager.State.PLAYING, result.reply.take(40))
                ttsEngine.speak(result.reply)
            }
        }
    }

    private fun isUnresolved(result: LocalCommandProcessor.Result): Boolean {
        return result.action == null && (
            result.reply.startsWith("Sorry, I didn't understand") ||
                result.reply.contains("ready to help", ignoreCase = true)
            )
    }

    override fun onError(errorCode: Int) {
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

        val retryDelays = longArrayOf(900L)
        val shouldRetry = errorCode == SpeechRecognizer.ERROR_AUDIO && sttFailureCount <= retryDelays.size

        if (shouldRetry) {
            val idx = (sttFailureCount - 1).coerceAtLeast(0).coerceAtMost(retryDelays.size - 1)
            val delayMs = retryDelays.getOrNull(idx) ?: 3000L
            Log.d(TAG, "Transient STT error; scheduling retry #$sttFailureCount (delay=${delayMs}ms)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (serviceState != ServiceState.LISTENING) {
                        transitionTo(ServiceState.LISTENING)
                        overlayManager.show(OverlayManager.State.LISTENING)
                        pushEvent(mapOf("state" to "LISTENING"))
                        updateNotification("I'm listening…")
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
            }, delayMs)
            return
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            sttRetryInFlight = false
            abandonAudioFocus()

            if (msg != null) {
                ttsEngine.speak(msg)
            }
            resetToIdle()
            pushEvent(mapOf("state" to "IDLE"))
            overlayManager.hideDelayed(800)
            sttFailureCount = 0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LocalTtsEngine.Listener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSpeechStart() {
        Log.d(TAG, "TTS speaking")
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

                val postTtsDelay = prefs.getLong("stt_post_tts_ms", 100L).coerceIn(0L, 250L)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (audioStreamer?.isConnected() == true) {
                        audioStreamer?.sendWakeWordTriggered()
                        requestAudioFocus()
                        captureAndSendAudio()
                    } else {
                        try {
                            val gotFocus = requestAudioFocus()
                            if (!gotFocus) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    try {
                                        requestAudioFocus()
                                        sttEngine.startListening()
                                    } catch (e: Exception) { Log.w(TAG, "STT start failed after ack (delayed): ${e.message}") }
                                }, 700)
                            } else {
                                sttEngine.startListening()
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
            wakeWordManager = WakeWordManager(applicationContext, PICOVOICE_ACCESS_KEY)
            wakeWordManager?.initialize()

            val activeWakeEngine = wakeWordManager?.activeEngine
            if (activeWakeEngine is HeyBroPhraseWakeEngine) {
                activeWakeEngine.setWakeCallback {
                    if (serviceState == ServiceState.IDLE) {
                        onWakeWordDetected()
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

        val activeEngine = wakeWordManager?.getActiveEngineName() ?: ""
        if (activeEngine.contains("HeyBroPhraseWakeEngine", ignoreCase = true)) {
            Log.i(TAG, "Wake phrase detection is handled by dedicated SpeechRecognizer; skipping generic audio loop")
            return
        }

        audioJob = scope.launch {
            var recorder: AudioRecord? = null
            try {
                val frameLength = wakeWordManager?.getRequiredFrameLength() ?: 512
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, frameLength * 2)

                val createdRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                recorder = createdRecorder
                synchronized(audioRecordLock) {
                    audioRecord = createdRecorder
                    createdRecorder.startRecording()
                }

                val buffer = ShortArray(frameLength)

                Log.i(TAG, "Wake word audio loop started (engine: $activeEngineName)")

                while (isActive) {
                    val rec = synchronized(audioRecordLock) { audioRecord } ?: break
                    if (rec !== recorder) break // Ownership changed
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                    
                    val read = rec.read(buffer, 0, frameLength)
                    if (read < 0) {
                        Log.w(TAG, "Wake loop AudioRecord read error: $read")
                        break
                    }
                    if (read != frameLength) continue

                    if (serviceState == ServiceState.IDLE) {
                        if (wakeWordManager?.process(buffer) == true) {
                            Log.i(TAG, "Wake word detected by ${wakeWordManager?.getActiveEngineName()}")
                            onWakeWordDetected()
                        }
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

    private fun onWakeWordDetected() {
        val nowMs = SystemClock.elapsedRealtime()
        if (serviceState != ServiceState.IDLE) return
        if (nowMs - lastWakeAtMs < 8_000L) {
            Log.d(TAG, "Wake cooldown active; ignoring extra trigger")
            return
        }
        if (nowMs - lastFailedListenAtMs < 12_000L) {
            Log.d(TAG, "Recent STT failure cooldown active; not re-triggering wake detection")
            return
        }

        lastWakeAtMs = nowMs

        try {
            audioJob?.cancel()
            audioJob = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel audioJob: ${e.message}")
        }
        releaseWakeRecorder()

        transitionTo(ServiceState.LISTENING)
        playFeedback()

        val engineName = wakeWordManager?.getActiveEngineName() ?: ""
        if (engineName.contains("Native", ignoreCase = true) ||
            engineName.contains("ViolaWake", ignoreCase = true) ||
            engineName.contains("HeyBroPhraseWakeEngine", ignoreCase = true)) {
            try {
                wakeWordManager?.release()
            } catch (_: Exception) {}
            activeEngineName = "None"
        }

        overlayManager.show(OverlayManager.State.LISTENING)
        pushEvent(mapOf("state" to "LISTENING", "activeEngine" to activeEngineName))
        updateNotification("I'm listening…")

        val greetings = listOf(
            "Hi Sandip, what can I do for you?",
            "Hey Sandip, how can I help?",
            "I'm listening, Sandip.",
            "What's up, Sandip?"
        )
        
        awaitingCommandAfterAck = true
        val ackDelay = prefs.getLong("stt_ack_ms", 250L)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!serviceDestroyed && serviceState == ServiceState.LISTENING) {
                ttsEngine.speak(greetings.random())
            }
        }, ackDelay)
    }

    fun manualTrigger() {
        if (serviceState == ServiceState.IDLE) {
            Log.d(TAG, "Manual push-to-talk trigger")
            onWakeWordDetected()
        } else if (serviceState == ServiceState.LISTENING) {
            Log.d(TAG, "Manual push-to-talk restart")
            captureJob?.cancel()
            if (audioStreamer?.isConnected() == true) {
                audioStreamer?.sendWakeWordTriggered()
                requestAudioFocus()
                captureAndSendAudio()
            } else {
                requestAudioFocus()
                sttEngine.startListening()
            }
        } else if (serviceState == ServiceState.SPEAKING) {
            ttsEngine.stop()
            resetToIdle()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onWakeWordDetected()
            }, 200)
        }
    }

    private fun resetToIdle() {
        transitionTo(ServiceState.IDLE)
        sileroVad?.resetStates()
        captureJob?.cancel()
        sttEngine.stopListening()

        synchronized(audioRecordLock) {
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try { audioRecord?.startRecording() } catch (_: Exception) {}
            }
        }

        if (wakeWordManager?.activeEngine == null) {
            scheduleWakeReinit()
        }

        updateNotification("Say \"Hey Bro\" to activate")
    }

    private fun scheduleWakeReinit(attemptsLeft: Int = 4) {
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
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ scheduleWakeReinit(attemptsLeft - 1) }, 800)
            return
        }

        try {
            initWakeWordEngine()
        } catch (e: Exception) {
            Log.w(TAG, "WakeWordManager re-init failed: ${e.message}")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ scheduleWakeReinit(attemptsLeft - 1) }, 800)
        }
    }

    @Synchronized
    private fun transitionTo(newState: ServiceState) {
        Log.d(TAG, "State: $serviceState → $newState")
        serviceState = newState
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
        captureJob?.cancel()
        captureJob = scope.launch {
            var recorder: AudioRecord? = null
            try {
                try { sttEngine.stopListening() } catch (_: Exception) {}

                val sampleRate = SAMPLE_RATE
                val frameSize = 512
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = maxOf(minBuf, frameSize * 2)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                recorder.startRecording()

                val shortBuf = ShortArray(frameSize)
                var speechDetected = false
                var silenceFrames = 0
                val maxSilenceFrames = 14
                val noSpeechTimeoutMs = 4_000L
                val maxDurationMs = 10_000L
                val startMs = SystemClock.elapsedRealtime()

                Log.d(TAG, "Backend capture started")

                while (isActive && SystemClock.elapsedRealtime() - startMs < maxDurationMs) {
                    if (audioStreamer?.isConnected() != true) {
                        Log.w(TAG, "Backend disconnected during capture; handing off to local STT")
                        requestAudioFocus()
                        try { sttEngine.startListening() } catch (fallbackError: Exception) {
                            Log.w(TAG, "Local STT fallback failed: ${fallbackError.message}")
                        }
                        return@launch
                    }

                    val r = recorder.read(shortBuf, 0, frameSize)
                    if (r < 0) {
                        Log.w(TAG, "Backend capture AudioRecord read error: $r")
                        break
                    }
                    if (r == 0) continue

                    if (!speechDetected && SystemClock.elapsedRealtime() - startMs >= noSpeechTimeoutMs) {
                        Log.d(TAG, "No speech detected within ${noSpeechTimeoutMs}ms")
                        // Do not send an empty buffer to the backend. Some STT
                        // providers turn silence into punctuation and trigger
                        // an unnecessary spoken response.
                        resetToIdle()
                        pushEvent(mapOf("state" to "IDLE"))
                        break
                    }

                    val vadScore = try { sileroVad?.isSpeech(shortBuf, sampleRate) ?: -1f } catch (_: Exception) { -1f }
                    val energy = shortBuf.take(r).sumOf { sample ->
                        val normalized = sample.toDouble() / Short.MAX_VALUE
                        normalized * normalized
                    }.let { kotlin.math.sqrt(it / r.coerceAtLeast(1)) }.toFloat()
                    val prob = if (vadScore >= 0f) vadScore else if (energy >= 0.010f) 0.8f else 0.05f
                    if (!speechDetected) {
                        if (prob > 0.30f || energy >= 0.010f) {
                            speechDetected = true
                        }
                    } else {
                        if (prob < 0.20f && energy < 0.006f) {
                            silenceFrames++
                        } else {
                            silenceFrames = 0
                        }
                    }

                    val bytes = ByteArray(r * 2)
                    var bi = 0
                    for (i in 0 until r) {
                        val s = shortBuf[i]
                        bytes[bi++] = (s.toInt() and 0xFF).toByte()
                        bytes[bi++] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }

                    try { audioStreamer?.sendAudioChunk(bytes) } catch (e: Exception) { Log.w(TAG, "sendAudioChunk failed: ${e.message}") }

                    if (speechDetected && silenceFrames >= maxSilenceFrames) {
                        Log.d(TAG, "Detected end of speech (silenceFrames=$silenceFrames)")
                        break
                    }
                }

                if (speechDetected) {
                    try { audioStreamer?.sendEndOfSpeech() } catch (e: Exception) { Log.w(TAG, "sendEndOfSpeech failed: ${e.message}") }
                }

                Log.d(TAG, "Backend capture finished; waiting for transcript")
                scheduleBackendFallback()

            } catch (e: Exception) {
                Log.e(TAG, "captureAndSendAudio error: ${e.message}")
                if (!serviceDestroyed && serviceState == ServiceState.LISTENING) {
                    requestAudioFocus()
                    try { sttEngine.startListening() } catch (_: Exception) {}
                }
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Exception) {}
                try {
                    recorder?.release()
                } catch (_: Exception) {}
                captureJob = null
            }
        }
    }

    private fun scheduleBackendFallback() {
        backendResponseHandler.removeCallbacks(backendFallbackRunnable)
        backendFallbackPending = true
        backendResponseHandler.postDelayed(backendFallbackRunnable, 6_000L)
    }

    private fun cancelBackendFallback() {
        backendFallbackPending = false
        backendResponseHandler.removeCallbacks(backendFallbackRunnable)
    }

    private fun pushEvent(data: Map<String, String>) {
        val json = JSONObject(data).toString()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            eventSink?.success(json)
        }
    }

    private fun updateNotification(text: String) {
        Log.d(TAG, "Service state notification: $text")
    }
}
