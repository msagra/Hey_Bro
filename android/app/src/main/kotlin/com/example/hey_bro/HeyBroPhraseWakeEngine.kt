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

class HeyBroPhraseWakeEngine(private val context: Context) : IWakeWordEngine {
    private var recognizer: SpeechRecognizer? = null
    private var callback: (() -> Unit)? = null
    private var isListening = false
    private var isReleased = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Error burst protection to avoid tight error loops on some OEM devices
    private var errorBurstCount = 0
    private var lastErrorAtMs: Long = 0L

    fun setWakeCallback(callback: () -> Unit) {
        this.callback = callback
    }

    override fun initialize(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("HeyBroPhraseWakeEngine", "Speech recognizer unavailable")
            return false
        }

        isReleased = false
        errorBurstCount = 0
        lastErrorAtMs = 0L
        return try {
            // Ensure recognizer is created on main thread
            mainHandler.post {
                try {
                    if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d("HeyBroPhraseWakeEngine", "Ready for wake phrase")
                        }

                        override fun onBeginningOfSpeech() { isListening = true }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { isListening = false }

                        override fun onError(error: Int) {
                            isListening = false
                            Log.w("HeyBroPhraseWakeEngine", "Wake phrase STT error: $error")

                            val now = System.currentTimeMillis()
                            if (now - lastErrorAtMs > 5_000L) {
                                // reset burst window
                                errorBurstCount = 0
                            }
                            lastErrorAtMs = now
                            errorBurstCount++

                            // If recognizer busy or client, allow a longer cooldown
                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                                Log.w("HeyBroPhraseWakeEngine", "Busy/client error — delaying restart")
                                if (!isReleased) {
                                    mainHandler.postDelayed({ if (!isReleased) startListening() }, 1500)
                                }
                                return
                            }

                            // If many errors in short time, fully reset recognizer and back off
                            if (errorBurstCount >= 6) {
                                Log.w("HeyBroPhraseWakeEngine", "Error burst detected ($errorBurstCount); resetting recognizer and backing off")
                                try {
                                    recognizer?.stopListening()
                                    recognizer?.cancel()
                                    recognizer?.destroy()
                                } catch (_: Exception) {}
                                recognizer = null

                                // schedule re-init after backoff
                                if (!isReleased) {
                                    mainHandler.postDelayed({
                                        if (!isReleased) {
                                            initialize()
                                        }
                                    }, 4000)
                                }
                                return
                            }

                            // Otherwise do a gentle retry with gradual backoff
                            val backoffMs = (400L * errorBurstCount).coerceAtMost(2000L)
                            if (!isReleased) {
                                mainHandler.postDelayed({ if (!isReleased) startListening() }, backoffMs)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val transcript = matches?.firstOrNull()?.lowercase() ?: ""
                            Log.d("HeyBroPhraseWakeEngine", "Wake transcript: $transcript")

                            if (transcript.contains("hey bro")) {
                                callback?.invoke()
                            }
                            if (!isReleased) {
                                mainHandler.postDelayed({
                                    if (!isReleased) startListening()
                                }, 250)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening()
                } catch (e: Exception) {
                    Log.e("HeyBroPhraseWakeEngine", "Init failed (main): ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e("HeyBroPhraseWakeEngine", "Init failed: ${e.message}")
            false
        }
    }

    private fun startListening() {
        if (recognizer == null || isListening || isReleased) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }

        try {
            recognizer?.cancel()
            recognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            Log.e("HeyBroPhraseWakeEngine", "startListening failed: ${e.message}")
        }
    }

    override fun process(frame: ShortArray): Boolean = false

    override fun release() {
        isReleased = true
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        callback = null
    }

    override fun getEngineName(): String = "HeyBroPhraseWakeEngine"

    override fun getRequiredFrameLength(): Int = 512
}
