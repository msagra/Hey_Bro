package com.example.hey_bro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class ViolaWakeEngine(
    private val context: Context,
    private val targetPhrase: String = "hey bro"
) : IWakeWordEngine {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isDetected = false
    private var isListening = false

    override fun initialize(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("ViolaWakeEngine", "Speech recognition not available on this device.")
            return false
        }

        return try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                        startListening()
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            startListening()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null) {
                            for (match in matches) {
                                if (match.equals(targetPhrase, ignoreCase = true) || match.contains(targetPhrase, ignoreCase = true)) {
                                    isDetected = true
                                    break
                                }
                            }
                        }
                        startListening()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null) {
                            for (match in matches) {
                                if (match.equals(targetPhrase, ignoreCase = true) || match.contains(targetPhrase, ignoreCase = true)) {
                                    isDetected = true
                                    break
                                }
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening()
            }
            Log.i("ViolaWakeEngine", "ViolaWake initialized for '$targetPhrase'")
            true
        } catch (e: Exception) {
            Log.e("ViolaWakeEngine", "Error initializing: ${e.message}")
            false
        }
    }

    private fun startListening() {
        if (isListening || speechRecognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
        isListening = true
    }

    override fun process(frame: ShortArray): Boolean {
        if (isDetected) {
            isDetected = false
            return true
        }
        return false
    }

    override fun release() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    override fun getEngineName(): String = "ViolaWake ($targetPhrase)"

    override fun getRequiredFrameLength(): Int = 512
}
