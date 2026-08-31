package com.example.hey_bro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech-to-text using Android's built-in SpeechRecognizer.
 *
 * IMPORTANT: SpeechRecognizer MUST be created, used, and destroyed on the
 * main (UI) thread. Every public method here dispatches to mainHandler.
 */
class LocalSttEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalSttEngine"
    }

    interface Listener {
        fun onResult(transcript: String)
        fun onError(errorCode: Int)
        fun onSpeechBegin()
        fun onPartialResult(transcript: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    @Volatile private var destroyed = false

    // ── State flags (accessed only on main thread) ────────────────────────────
    private var isListening = false
    private var isInitialized = false
    private var startGeneration = 0L
    private var internalRetryCount = 0

    // Watchdog to recover from stuck or extremely short listening sessions
    private var listenStartMs: Long = 0L
    // Increase listen window for noisy devices; allow longer capture before forcing timeout
    private val MAX_LISTEN_MS = 20_000L
    // If NO_MATCH occurs within this threshold consider it a "start too soon" and retry with backoff
    private val SHORT_START_THRESHOLD_MS = 1200L
    // Track whether this listening session was initiated after TTS to apply extra settling delay
    @Volatile private var postTtsStart = false
    private val watchdogRunnable = Runnable {
        if (isListening && System.currentTimeMillis() - listenStartMs >= MAX_LISTEN_MS) {
            Log.w(TAG, "Watchdog: listening timeout; cancelling recognizer")
            try {
                recognizer?.cancel()
            } catch (_: Exception) {}
            isListening = false
            listener?.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }
    }

    fun initialize(listener: Listener) {
        destroyed = false
        this.listener = listener
        mainHandler.post {
            if (destroyed) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "SpeechRecognizer not available on this device")
                return@post
            }
            recreateRecognizerLocked()
            isInitialized = true
            Log.i(TAG, "LocalSttEngine initialized on main thread")
        }
    }

    /** Must be safe to call from any thread — dispatched to main internally. */
    fun startListening() {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            if (!isInitialized || recognizer == null) {
                Log.w(TAG, "startListening called before init – reinitializing")
                listener?.let { initialize(it) }
                mainHandler.postDelayed({
                    if (!destroyed) startListeningOnMain()
                }, 500)
                return@post
            }
            internalRetryCount = 0
            startListeningOnMain()
        }
    }

    /** Start listening with a small pipeline-settling delay (use after TTS playback). */
    fun startListeningPostTts(delayMs: Long = 200L) {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            if (!isInitialized || recognizer == null) {
                listener?.let { initialize(it) }
                mainHandler.postDelayed({
                    if (!destroyed) {
                        postTtsStart = true
                        startListeningOnMain()
                    }
                }, 500)
                return@post
            }
            internalRetryCount = 0
            postTtsStart = true
            mainHandler.postDelayed({
                if (!destroyed) startListeningOnMain()
            }, delayMs)
        }
    }

    /** Must be safe to call from any thread. */
    fun stopListening() {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            startGeneration++
            internalRetryCount = 0
            if (isListening) {
                try { recognizer?.stopListening() } catch (_: Exception) {}
                isListening = false
            }
            try { recognizer?.cancel() } catch (_: Exception) {}
            mainHandler.removeCallbacks(watchdogRunnable)
        }
    }

    fun destroy() {
        destroyed = true
        mainHandler.post {
            startGeneration++
            internalRetryCount = 0
            try { recognizer?.stopListening() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            isListening = false
            isInitialized = false
            mainHandler.removeCallbacks(watchdogRunnable)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun recreateRecognizerLocked() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        
        recognizer = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                Log.i(TAG, "Creating on-device SpeechRecognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                Log.i(TAG, "Creating system SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create specialized recognizer, falling back to default: ${e.message}")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        
        recognizer?.setRecognitionListener(innerListener)
        isListening = false
    }

    private fun startListeningOnMain() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            listener?.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        if (recognizer == null) {
            recreateRecognizerLocked()
        }

        if (isListening) {
            Log.d(TAG, "Already listening – cancelling previous session first")
            try { recognizer?.cancel() } catch (_: Exception) {}
            isListening = false
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }

        try {
            startGeneration++
            postTtsStart = false
            recognizer?.startListening(intent)
            isListening = true
            listenStartMs = System.currentTimeMillis()
            mainHandler.removeCallbacks(watchdogRunnable)
            mainHandler.postDelayed(watchdogRunnable, MAX_LISTEN_MS)
            Log.d(TAG, "STT listening started")
        } catch (e: Exception) {
            isListening = false
            Log.e(TAG, "SpeechRecognizer startListening failed: ${e.message}")
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            listener?.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private val innerListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech began")
            mainHandler.removeCallbacks(watchdogRunnable)
            listener?.onSpeechBegin()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            mainHandler.removeCallbacks(watchdogRunnable)
            Log.d(TAG, "Speech ended – waiting for results")
        }

        override fun onError(error: Int) {
            isListening = false
            mainHandler.removeCallbacks(watchdogRunnable)
            Log.w(TAG, "STT error: $error")

            // Error 11 = ERROR_SERVER_DISCONNECTED, 8 = ERROR_RECOGNIZER_BUSY, 5 = ERROR_CLIENT
            if (error == 11 || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                if (internalRetryCount < 2) {
                    internalRetryCount++
                    val delayMs = 600L * internalRetryCount
                    Log.w(TAG, "Recoverable STT error ($error); rebuilding recognizer (retry $internalRetryCount/2 in ${delayMs}ms)")
                    mainHandler.postDelayed({
                        if (!isInitialized || destroyed) return@postDelayed
                        try {
                            recreateRecognizerLocked()
                            startListeningOnMain()
                        } catch (retryError: Exception) {
                            Log.w(TAG, "Recognizer retry failed: ${retryError.message}")
                            listener?.onError(error)
                        }
                    }, delayMs)
                    return
                }
            }

            if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                val now = System.currentTimeMillis()
                val startedAgo = if (listenStartMs == 0L) Long.MAX_VALUE else now - listenStartMs
                if (startedAgo < SHORT_START_THRESHOLD_MS && internalRetryCount < 2) {
                    internalRetryCount++
                    val retryDelay = if (postTtsStart) 400L else 600L
                    Log.d(TAG, "Quick NO_MATCH after $startedAgo ms — measured retry #$internalRetryCount (delay=${retryDelay}ms)")
                    mainHandler.postDelayed({
                        try {
                            if (!isInitialized || destroyed) return@postDelayed
                            if (recognizer == null) recreateRecognizerLocked()
                            startListeningOnMain()
                        } catch (retryError: Exception) {
                            Log.w(TAG, "Measured retry failed: ${retryError.message}")
                        }
                    }, retryDelay)
                    return
                }
            }

            internalRetryCount = 0
            listener?.onError(error)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            internalRetryCount = 0
            mainHandler.removeCallbacks(watchdogRunnable)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val best = matches
                ?.mapIndexed { index, value ->
                    val text = value.trim()
                    val score = scores?.getOrNull(index) ?: 0.5f
                    Triple(text, score, index)
                }
                ?.filter { it.first.isNotBlank() }
                ?.maxWithOrNull(compareBy<Triple<String, Float, Int>> { it.second }.thenBy { it.first.length })
                ?.first
                ?: ""
            Log.i(TAG, "STT result: \"$best\"")
            if (best.isNotBlank()) {
                listener?.onResult(best)
            } else {
                listener?.onError(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull { it.isNotBlank() }?.trim() ?: return
            listener?.onPartialResult(best)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
