package com.example.hey_bro

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * On-device Text-to-Speech using Android's built-in TTS engine.
 * Completely free, works offline.
 */
class LocalTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalTtsEngine"
    }

    interface Listener {
        fun onSpeechStart()
        fun onSpeechDone()
    }

    private var tts: TextToSpeech? = null
    private var listener: Listener? = null
    private var isReady = false

    fun initialize(listener: Listener) {
        this.listener = listener
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS language not supported – trying default locale")
                    tts?.setLanguage(Locale.getDefault())
                }

                configureDeepMaleVoice()
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(0.72f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS speaking: $utteranceId")
                        listener.onSpeechStart()
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS done: $utteranceId")
                        listener.onSpeechDone()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        listener.onSpeechDone()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error $errorCode for $utteranceId")
                        listener.onSpeechDone()
                    }
                })

                isReady = true
                Log.i(TAG, "LocalTtsEngine ready")
            } else {
                Log.e(TAG, "TTS initialization failed with status $status")
            }
        }
    }

    private fun configureDeepMaleVoice() {
        val availableVoices = tts?.voices ?: emptySet()
        val preferred = availableVoices
            .filter { voice ->
                val name = voice.name.lowercase(Locale.US)
                val localeMatch = voice.locale == Locale.US || voice.locale.language == Locale.US.language
                localeMatch && (
                    name.contains("male") ||
                    name.contains("deep") ||
                    name.contains("bass") ||
                    name.contains("danny") ||
                    name.contains("john") ||
                    name.contains("voice1")
                )
            }
            .sortedBy { it.name.lowercase(Locale.US) }
            .firstOrNull()

        if (preferred != null) {
            try {
                tts?.voice = preferred
                Log.i(TAG, "Selected deep male voice: ${preferred.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not apply preferred male voice: ${e.message}")
            }
        } else {
            Log.i(TAG, "No deep male voice available; using lower pitch fallback")
        }
    }

    fun speak(text: String) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, dropping: \"$text\"")
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        Log.d(TAG, "TTS speak: \"$text\"")
    }

    fun stop() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
