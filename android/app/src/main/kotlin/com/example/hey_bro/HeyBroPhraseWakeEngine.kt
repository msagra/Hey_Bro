package com.example.hey_bro

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
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
    private var restartScheduled = false
    private var wakeSessionStartedAtMs = 0L
    private var wakeTriggered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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
        restartScheduled = false
        errorBurstCount = 0
        lastErrorAtMs = 0L
        return try {
            mainHandler.post {
                try {
                    if (recognizer == null) recognizer = createRecognizer()
                    recognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            wakeSessionStartedAtMs = System.currentTimeMillis()
                            wakeTriggered = false
                            scheduleStallRecovery()
                            Log.d("HeyBroPhraseWakeEngine", "Ready for wake phrase")
                        }

                        override fun onBeginningOfSpeech() {
                            isListening = true
                            mainHandler.removeCallbacks(stallRecoveryRunnable)
                        }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                            mainHandler.removeCallbacks(stallRecoveryRunnable)
                        }

                        override fun onError(error: Int) {
                            isListening = false
                            mainHandler.removeCallbacks(stallRecoveryRunnable)
                            val isExpectedNoMatch = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            if (!isExpectedNoMatch) {
                                Log.w("HeyBroPhraseWakeEngine", "Wake phrase recognizer error: $error")
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastErrorAtMs > 5_000L) {
                                errorBurstCount = 0
                            }
                            lastErrorAtMs = now
                            errorBurstCount++

                            if (errorBurstCount >= 6) {
                                try {
                                    recognizer?.stopListening()
                                    recognizer?.cancel()
                                    recognizer?.destroy()
                                } catch (_: Exception) {}
                                recognizer = null

                                if (!isReleased) {
                                    mainHandler.postDelayed({
                                        if (!isReleased) {
                                            initialize()
                                        }
                                    }, 4000)
                                }
                                return
                            }

                            val backoffMs = if (isExpectedNoMatch) {
                                150L
                            } else {
                                (300L * errorBurstCount).coerceAtMost(1500L)
                            }
                            scheduleRestart(backoffMs)
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            mainHandler.removeCallbacks(stallRecoveryRunnable)
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val transcripts = matches.orEmpty().map { normalizeWakeText(it) }
                            
                            val wakePhrases = wakePhrases()
                            val matched = transcripts.any { candidate ->
                                isWakePhraseMatched(candidate, wakePhrases)
                            }
                            Log.d("HeyBroPhraseWakeEngine", "Wake candidates: $transcripts | matched=$matched")

                            if (matched && !wakeTriggered) {
                                wakeTriggered = true
                                restoreSystemSound()
                                Log.i("HeyBroPhraseWakeEngine", "Wake phrase detected: $transcripts")
                                callback?.invoke()
                            } else {
                                scheduleRestart(150L)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            if (wakeTriggered) return
                            val partials = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                .orEmpty()
                            val matched = partials
                                .map { normalizeWakeText(it) }
                                .any { candidate -> isWakePhraseMatched(candidate, wakePhrases()) }
                            if (matched && !wakeTriggered) {
                                wakeTriggered = true
                                restoreSystemSound()
                                Log.i("HeyBroPhraseWakeEngine", "Wake phrase matched in partial result")
                                callback?.invoke()
                            }
                        }
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

    private fun suppressSystemSound() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, true)
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_NOTIFICATION, true)
            }
        } catch (_: Exception) {}
    }

    private fun restoreSystemSound() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, false)
                @Suppress("DEPRECATION")
                audioManager?.setStreamMute(AudioManager.STREAM_NOTIFICATION, false)
            }
        } catch (_: Exception) {}
    }

    private fun startListening() {
        if (recognizer == null || isListening || isReleased) return

        suppressSystemSound()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // Suppress system audio beeps / prompts / tones during background wake detection
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra("android.speech.extra.PROMPT_SOUND", false)
            putExtra("android.speech.extra.NO_AUDIO_FEEDBACK", true)
            putExtra("android.speech.extra.SUPPRESS_START_BEEP", true)
            putExtra("android.speech.extra.SUPPRESS_END_BEEP", true)
            putExtra("android.speech.extra.SUPPRESS_SOUND", true)
            putExtra("suppress_sound", true)
            putExtra("android.speech.extra.HIDE_PARTIAL_TRAILING_PUNCTUATION", true)
        }

        try {
            recognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            Log.e("HeyBroPhraseWakeEngine", "startListening failed: ${e.message}")
            scheduleRestart(500L)
        }
    }

    private val stallRecoveryRunnable = Runnable {
        if (!isReleased && isListening &&
            System.currentTimeMillis() - wakeSessionStartedAtMs >= 8_000L
        ) {
            Log.w("HeyBroPhraseWakeEngine", "Wake recognizer stalled; rebuilding session")
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (_: Exception) {}
            recognizer = null
            isListening = false
            mainHandler.postDelayed({
                if (!isReleased) initialize()
            }, 250L)
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            Log.i("HeyBroPhraseWakeEngine", "Using on-device wake recognizer")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.i("HeyBroPhraseWakeEngine", "Using system wake recognizer")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun wakePhrases(): List<String> = listOf(
        "hey bro", "hello bro", "ok bro", "okay bro", "hey brother", "hi bro", "hey assistant",
        // Fuzzy phonetic near-misses that commonly occur in noisy environments
        "hey brow", "hey pro", "a bro", "hey bra", "hey boo",
        "hey bru", "he bro", "hay bro", "hey brot", "hey broe"
    )

    private fun isWakePhraseMatched(text: String, phrases: List<String>): Boolean {
        if (text.isBlank()) return false
        val words = text.split(" ").filter { it.isNotBlank() }
        for (phrase in phrases) {
            val pWords = phrase.split(" ")
            if (pWords.isEmpty()) continue
            // Check for exact phrase as whole words sequence
            for (i in 0..words.size - pWords.size) {
                if (words.subList(i, i + pWords.size) == pWords) {
                    return true
                }
            }
        }
        // Fuzzy substring containment — catches "hey brother" inside longer phrases
        // and partial recognition artifacts like "heybro"
        val joined = words.joinToString("")
        if (joined.contains("heybro") || joined.contains("hibro") ||
            joined.contains("okbro") || joined.contains("hellobro")) {
            return true
        }
        // Edit-distance-1 check for the primary wake phrase
        for (word in words) {
            if (word.length in 2..5 && editDistance(word, "bro") <= 1) {
                // Found a word close to "bro"; check if preceding word is close to "hey"/"hi"/"ok"
                val idx = words.indexOf(word)
                if (idx > 0) {
                    val prev = words[idx - 1]
                    if (prev in listOf("hey", "hi", "ok", "hello", "he", "hay", "a", "okay") ||
                        editDistance(prev, "hey") <= 1) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    private fun normalizeWakeText(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun scheduleStallRecovery() {
        mainHandler.removeCallbacks(stallRecoveryRunnable)
        mainHandler.postDelayed(stallRecoveryRunnable, 8_000L)
    }

    private fun scheduleRestart(delayMs: Long) {
        if (isReleased || restartScheduled) return
        restartScheduled = true
        mainHandler.postDelayed({
            restartScheduled = false
            if (!isReleased) startListening()
        }, delayMs)
    }

    override fun process(frame: ShortArray): Boolean = false

    override fun release() {
        isReleased = true
        isListening = false
        restartScheduled = false
        wakeTriggered = false
        wakeSessionStartedAtMs = 0L
        mainHandler.removeCallbacksAndMessages(null)
        restoreSystemSound()
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        callback = null
    }

    override fun getEngineName(): String = "HeyBroPhraseWakeEngine"

    override fun getRequiredFrameLength(): Int = 512
}
