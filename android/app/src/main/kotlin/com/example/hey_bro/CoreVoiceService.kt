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
import android.media.ToneGenerator
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
 *
 * Pipeline:
 *  1. Wake word detection  → WakeWordManager (failover chain)
 *  2. Speech-to-text       → LocalSttEngine (Android built-in, offline-capable)
 *  3. Command parsing      → LocalCommandProcessor (rule engine)
 *  4. Action execution     → DeviceOperatorService (accessibility)
 *  5. Text-to-speech       → LocalTtsEngine (Android built-in, offline)
 *
 * No internet, no API keys, no server required.
 */
class CoreVoiceService : Service(),
    LocalSttEngine.Listener,
    LocalTtsEngine.Listener {

    // ── Companion ─────────────────────────────────────────────────────────────
    companion object {
        private const val TAG             = "CoreVoiceService"
        private const val CHANNEL_ID      = "HeyBroChannel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE     = 16000

        var activeEngineName: String = "None"
        var eventSink: EventChannel.EventSink? = null
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
                val afReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                val res = am.requestAudioFocus(afReq)
                audioFocusRequestCompat = afReq
                audioFocusRequested = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val res = am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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

    // ── Audio loop ────────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var audioJob:    Job?         = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── VAD silence counter ───────────────────────────────────────────────────
    // We keep the audio loop for wake-word detection only; STT is done by LocalSttEngine
    private val PICOVOICE_ACCESS_KEY = "YOUR_PICOVOICE_ACCESS_KEY_HERE"
    private var lastWakeAtMs = 0L
    private var lastFailedListenAtMs = 0L

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        prefs = getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)

        overlayManager = OverlayManager(this)
        sttEngine      = LocalSttEngine(this)
        ttsEngine      = LocalTtsEngine(this)
        LocalCommandProcessor.initialize(applicationContext)

        sttEngine.initialize(this)
        ttsEngine.initialize(this)

        // Initialize optional backend audio streamer if user configured a WS URL
        try {
            val prefs = getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)
            backendWsUrl = prefs.getString("backend_ws", null)
            if (!backendWsUrl.isNullOrBlank()) {
                audioStreamer = AudioStreamer(object : AudioStreamerListener {
                    override fun onResponseComplete() { /* no-op */ }
                    override fun onConnectionStateChanged(connected: Boolean) {
                        Log.i(TAG, "AudioStreamer connected=$connected")
                    }
                    override fun onTranscriptReceived(text: String) {
                        // Called when backend returns a transcript; treat like STT result
                        onResult(text)
                    }
                    override fun onSpokenTextReceived(text: String) {
                        ttsEngine.speak(text)
                    }
                    override fun onActionReceived(payload: String) {
                        // Backend sent an action JSON – execute it
                        DeviceOperatorService.instance?.executeCommand(payload)
                    }
                })
                audioStreamer?.connect(backendWsUrl!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioStreamer init failed: ${e.message}")
            audioStreamer = null
            backendWsUrl = null
        }

        initWakeWordEngine()
        initVAD()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startWakeWordLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        audioJob?.cancel()
        wakeWordManager?.release()
        sileroVad?.close()
        sttEngine.destroy()
        ttsEngine.destroy()
        overlayManager.destroy()
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
        // Speech started – already in LISTENING state
        Log.d(TAG, "User began speaking")
        // reset transient failure counter when the user actually speaks
        sttFailureCount = 0
        // any retry flow is satisfied
        sttRetryInFlight = false
        abandonAudioFocus()
    }

    override fun onResult(transcript: String) {
        // Successful result — reset failure counter and clear retry state
        sttFailureCount = 0
        sttRetryInFlight = false
        abandonAudioFocus()

        Log.i(TAG, "STT result: \"$transcript\"")
        transitionTo(ServiceState.PROCESSING)
        pushEvent(mapOf("state" to "PROCESSING", "transcript" to transcript))
        overlayManager.show(OverlayManager.State.PROCESSING)

        // Run command processing off the main thread
        scope.launch(Dispatchers.Default) {
            var result = LocalCommandProcessor.process(transcript)
            if (result.reply.startsWith("Sorry, I didn't understand") || result.reply.contains("I’m ready to help")) {
                LocalCommandProcessor.interpretWithGemini(transcript)?.let { geminiResult ->
                    result = geminiResult
                    Log.i(TAG, "Used Gemini fallback for transcript: \"$transcript\"")
                }
            }

            // Execute device action if any (must post to main thread)
            result.action?.let { actionJson ->
                val actionStr = actionJson.optString("action")
                Log.d(TAG, "Executing action: $actionStr")

                if (actionStr == "sleep") {
                    // Stop listening and go truly idle
                    resetToIdle()
                    pushEvent(mapOf("state" to "IDLE"))
                    overlayManager.hideDelayed(500)
                    ttsEngine.speak(result.reply)
                    return@launch
                }

                DeviceOperatorService.instance?.executeCommand(actionJson.toString())
            }

            // Speak the reply
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                transitionTo(ServiceState.SPEAKING)
                pushEvent(mapOf("state" to "PLAYING", "response" to result.reply))
                overlayManager.show(OverlayManager.State.PLAYING, result.reply.take(40))
                ttsEngine.speak(result.reply)
            }
        }
    }

    override fun onError(errorCode: Int) {
        lastFailedListenAtMs = SystemClock.elapsedRealtime()
        sttFailureCount++

        val msg = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH       -> "I didn't catch that. Try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null  // silent – user just didn't speak
            SpeechRecognizer.ERROR_AUDIO          -> "Audio error. Please try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
            else                                  -> null  // suppress unknown errors silently
        }

        Log.w(TAG, "STT error $errorCode (count=$sttFailureCount)${if (msg != null) ": $msg" else " (silent)"}")

        // For transient errors, attempt retries with increasing back-off before giving up
        val retryDelays = longArrayOf(650L, 1200L, 2500L)
        val shouldRetry = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_AUDIO -> sttFailureCount <= retryDelays.size
            else -> false
        }

        if (shouldRetry) {
            val idx = (sttFailureCount - 1).coerceAtLeast(0).coerceAtMost(retryDelays.size - 1)
            val delayMs = retryDelays.getOrNull(idx) ?: 3000L
            Log.d(TAG, "Transient STT error; scheduling retry #$sttFailureCount (delay=${delayMs}ms)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    // Keep LISTENING state so user can continue speaking
                    if (serviceState != ServiceState.LISTENING) {
                        transitionTo(ServiceState.LISTENING)
                        overlayManager.show(OverlayManager.State.LISTENING)
                        pushEvent(mapOf("state" to "LISTENING"))
                        updateNotification("I'm listening…")
                    }

                    // mark that a retry flow is in-flight so wake engine is not reinitialized
                    sttRetryInFlight = true
                    requestAudioFocus()

                    try {
                        sttEngine.startListening()
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry startListening failed: ${e.message}")
                        // Fall through to final failure handling below
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Retry startListening failed outer: ${e.message}")
                }
            }, delayMs)
            return
        }

        // Final failure: speak a helpful message (if one exists) and go idle
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // Clear retry-in-flight and abandon audio focus
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
                // This TTS was the "Yes, I'm listening" ack — begin STT capture now
                awaitingCommandAfterAck = false
                transitionTo(ServiceState.LISTENING)
                overlayManager.show(OverlayManager.State.LISTENING)
                pushEvent(mapOf("state" to "LISTENING"))
                updateNotification("I'm listening…")

                // Start backend capture if connected, otherwise local STT
                // Small delay to ensure any TTS playback fully stops and audio focus is stable.
                // Use configurable post-ack delay (defaults to 800ms) so we can tune on problematic devices.
                val postTtsDelay = prefs.getLong("stt_post_tts_ms", 800L)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (audioStreamer?.isConnected() == true) {
                        audioStreamer?.sendWakeWordTriggered()
                        requestAudioFocus()
                        captureAndSendAudio()
                    } else {
                        try {
                            // Try requesting audio focus and ensure it's granted before starting STT
                            val gotFocus = requestAudioFocus()
                            if (!gotFocus) {
                                // Wait a bit longer for audio focus to stabilize, then try
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
                // Regular TTS reply finished — go idle
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
            val channel = NotificationChannel(
                CHANNEL_ID, "Hey Bro Service", NotificationManager.IMPORTANCE_LOW
            ).also { it.description = "Foreground service for Hey Bro assistant" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wake word audio loop  (only runs wake word detection; STT is separate)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startWakeWordLoop() {
        if (audioJob?.isActive == true) return

        val activeEngine = wakeWordManager?.getActiveEngineName() ?: ""
        if (activeEngine.contains("HeyBroPhraseWakeEngine", ignoreCase = true)) {
            Log.i(TAG, "Wake phrase detection is handled by dedicated SpeechRecognizer; skipping generic audio loop")
            return
        }

        audioJob = scope.launch {
            try {
                val frameLength = wakeWordManager?.getRequiredFrameLength() ?: 512
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, frameLength * 2)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                audioRecord?.startRecording()

                val buffer = ShortArray(frameLength)

                Log.i(TAG, "Wake word audio loop started (engine: $activeEngineName)")

                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, frameLength) ?: break
                    if (read != frameLength) continue

                    // Only check wake word when IDLE (not while STT is active)
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
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wake word / state transitions
    // ─────────────────────────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        val nowMs = SystemClock.elapsedRealtime()
        if (serviceState != ServiceState.IDLE) return  // ignore if busy
        if (nowMs - lastWakeAtMs < 8_000L) {
            Log.d(TAG, "Wake cooldown active; ignoring extra trigger")
            return
        }
        if (nowMs - lastFailedListenAtMs < 12_000L) {
            Log.d(TAG, "Recent STT failure cooldown active; not re-triggering wake detection")
            return
        }

        lastWakeAtMs = nowMs
        transitionTo(ServiceState.LISTENING)
        playFeedback()

        val engineName = wakeWordManager?.getActiveEngineName() ?: ""
        if (engineName.contains("Native", ignoreCase = true) ||
            engineName.contains("ViolaWake", ignoreCase = true) ||
            engineName.contains("HeyBroPhraseWakeEngine", ignoreCase = true)) {
            // These engines hold a SpeechRecognizer internally. Stop them before the main command
            // recognizer starts, otherwise Android creates concurrent recognition sessions and
            // immediately stops the command listening flow.
            try {
                wakeWordManager?.release()
            } catch (_: Exception) {
            }
            activeEngineName = "None"
        } else {
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
            } catch (_: Exception) {
            }
        }

        overlayManager.show(OverlayManager.State.LISTENING)
        pushEvent(mapOf("state" to "LISTENING"))
        updateNotification("I'm listening…")

        // Use configurable delays so the timing can be tuned per-device via prefs
        val prefs = getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)
        val ackDelay = prefs.getLong("stt_ack_ms", 400L)
        val sttStartDelay = prefs.getLong("stt_start_ms", 2200L)

        awaitingCommandAfterAck = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ttsEngine.speak("Yes, I'm listening")
        }, ackDelay)

        // Start STT after a configurable gap to avoid TTS/STT contention.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (audioStreamer?.isConnected() == true) {
                Log.d(TAG, "Using backend AudioStreamer for STT capture")
                audioStreamer?.sendWakeWordTriggered()
                requestAudioFocus()
                captureAndSendAudio()
            } else {
                requestAudioFocus()
                sttEngine.startListening()
            }
        }, sttStartDelay)
    }

    fun manualTrigger() {
        if (serviceState == ServiceState.IDLE) {
            Log.d(TAG, "Manual push-to-talk trigger")
            onWakeWordDetected()
        } else if (serviceState == ServiceState.SPEAKING) {
            // Allow interrupting the response
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
        sttEngine.stopListening()

        // Resume AudioRecord if it was paused for STT
        if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try { audioRecord?.startRecording() } catch (_: Exception) {}
        }

        // Re-init wake word engine if it was released (but defer while STT retries are in-flight)
        if (wakeWordManager?.activeEngine == null) {
            scheduleWakeReinit()
        }

        updateNotification("Say \"Hey Bro\" to activate")
    }

    // Schedule wake re-init but defer while transient STT retries are still happening.
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

        // If there are retries in flight, defer
        if (sttRetryInFlight || sttFailureCount > 0) {
            Log.d(TAG, "Deferring WakeWordManager re-init due to STT retry state (inFlight=$sttRetryInFlight count=$sttFailureCount) attemptsLeft=$attemptsLeft")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ scheduleWakeReinit(attemptsLeft - 1) }, 800)
            return
        }

        try {
            initWakeWordEngine()
        } catch (e: Exception) {
            Log.w(TAG, "WakeWordManager re-init failed: ${e.message}")
            // try again later
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
                    vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
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

        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
        } catch (e: Exception) {
            Log.w(TAG, "Tone skipped: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureAndSendAudio() {
        scope.launch {
            try {
                // Stop local STT to avoid conflicts
                try { sttEngine.stopListening() } catch (_: Exception) {}

                val sampleRate = SAMPLE_RATE
                val frameSize = 1024
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = maxOf(minBuf, frameSize * 2)

                val recorder = AudioRecord(
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
                val maxSilenceFrames = 20 // approx 20 frames ~ 640ms (frame duration ~32ms)
                val maxDurationMs = 12_000L
                val startMs = SystemClock.elapsedRealtime()

                Log.d(TAG, "Backend capture started")

                while (SystemClock.elapsedRealtime() - startMs < maxDurationMs) {
                    val r = recorder.read(shortBuf, 0, frameSize)
                    if (r <= 0) continue

                    val prob = try { sileroVad?.isSpeech(shortBuf, sampleRate) ?: 0f } catch (_: Exception) { 0f }
                    if (!speechDetected) {
                        if (prob > 0.45f) {
                            speechDetected = true
                            Log.d(TAG, "Speech started (prob=$prob)")
                        }
                    } else {
                        if (prob < 0.3f) {
                            silenceFrames++
                        } else {
                            silenceFrames = 0
                        }
                    }

                    // Convert shorts to little-endian bytes
                    val bytes = ByteArray(r * 2)
                    var bi = 0
                    for (i in 0 until r) {
                        val s = shortBuf[i]
                        bytes[bi++] = (s.toInt() and 0xFF).toByte()
                        bytes[bi++] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }

                    // Send to backend
                    try { audioStreamer?.sendAudioChunk(bytes) } catch (e: Exception) { Log.w(TAG, "sendAudioChunk failed: ${e.message}") }

                    if (speechDetected && silenceFrames >= maxSilenceFrames) {
                        Log.d(TAG, "Detected end of speech (silenceFrames=$silenceFrames)")
                        break
                    }
                }

                try { audioStreamer?.sendEndOfSpeech() } catch (e: Exception) { Log.w(TAG, "sendEndOfSpeech failed: ${e.message}") }

                try {
                    recorder.stop()
                } catch (_: Exception) {}
                try {
                    recorder.release()
                } catch (_: Exception) {}

                Log.d(TAG, "Backend capture finished; waiting for transcript from server")

            } catch (e: Exception) {
                Log.e(TAG, "captureAndSendAudio error: ${e.message}")
                try { sttEngine.startListening() } catch (_: Exception) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EventChannel + Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun pushEvent(data: Map<String, String>) {
        val json = JSONObject(data).toString()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            eventSink?.success(json)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hey Bro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
