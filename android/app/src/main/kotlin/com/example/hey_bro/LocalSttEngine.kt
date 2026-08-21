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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null

    // ── State flags (accessed only on main thread) ────────────────────────────
    private var isListening = false
    private var isInitialized = false

    // Watchdog to recover from stuck or extremely short listening sessions
    private var listenStartMs: Long = 0L
    // Increase listen window for noisy devices; allow longer capture before forcing timeout
    private val MAX_LISTEN_MS = 20_000L
    // If NO_MATCH occurs within this threshold consider it a "start too soon" and retry with backoff
    private val SHORT_START_THRESHOLD_MS = 800L
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
        this.listener = listener
        mainHandler.post {
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
        mainHandler.post {
            if (!isInitialized || recognizer == null) {
                Log.w(TAG, "startListening called before init – reinitializing")
                listener?.let { initialize(it) }
                mainHandler.postDelayed({ startListeningOnMain() }, 500)
                return@post
            }
            startListeningOnMain()
        }
    }

    /** Must be safe to call from any thread. */
    fun stopListening() {
        mainHandler.post {
            if (isListening) {
                try { recognizer?.stopListening() } catch (_: Exception) {}
                isListening = false
            }
            try { recognizer?.cancel() } catch (_: Exception) {}
            mainHandler.removeCallbacks(watchdogRunnable)
        }
    }

    fun destroy() {
        mainHandler.post {
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
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(innerListener)
        isListening = false
    }

    private fun startListeningOnMain() {
        if (recognizer == null) {
            recreateRecognizerLocked()
        }

        if (isListening) {
            Log.d(TAG, "Already listening – cancelling previous session first")
            recognizer?.cancel()
            isListening = false
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        try {
            recognizer?.cancel()
            recognizer?.startListening(intent)
            isListening = true
            listenStartMs = System.currentTimeMillis()
            mainHandler.removeCallbacks(watchdogRunnable)
            mainHandler.postDelayed(watchdogRunnable, MAX_LISTEN_MS)
            Log.d(TAG, "Listening started with extended capture window for strong command recognition")
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
            // Clear watchdog on actual speech begin
            mainHandler.removeCallbacks(watchdogRunnable)
            listener?.onSpeechBegin()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            // clear watchdog; waiting for results
            mainHandler.removeCallbacks(watchdogRunnable)
            Log.d(TAG, "Speech ended – waiting for results")
        }

        override fun onError(error: Int) {
            isListening = false
            mainHandler.removeCallbacks(watchdogRunnable)
            Log.w(TAG, "STT error: $error")

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                Log.w(TAG, "Busy/client error; rebuilding recognizer and retrying after a short settle window")
                // Avoid clearing all mainHandler callbacks; only schedule a safe rebuild + retry
                mainHandler.postDelayed({
                    try {
                        recreateRecognizerLocked()
                    } catch (_: Exception) {}
                    try {
                        startListeningOnMain()
                    } catch (_: Exception) {}
                }, 800)
                listener?.onError(error)
                return
            }

            if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                val now = System.currentTimeMillis()
                val startedAgo = if (listenStartMs == 0L) Long.MAX_VALUE else now - listenStartMs
                if (startedAgo < SHORT_START_THRESHOLD_MS) {
                    Log.d(TAG, "Quick NO_MATCH after $startedAgo ms — doing a measured retry with longer backoff")
                    // Give device/system a moment to settle (TTS may still be winding down); retry with progressive backoff
                    mainHandler.postDelayed({
                        try {
                            if (recognizer == null) recreateRecognizerLocked()
                            startListeningOnMain()
                        } catch (_: Exception) {
                            Log.w(TAG, "Measured retry failed: ${'$'}{_.message}")
                        }
                    }, 700)
                }
            }
            listener?.onError(error)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim() ?: ""
            Log.i(TAG, "STT result: \"$best\"")
            if (best.isNotBlank()) {
                listener?.onResult(best)
            } else {
                listener?.onError(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
