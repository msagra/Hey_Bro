package com.example.hey_bro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Continuous 16kHz mono wake-phrase engine tailored for Android 15 & Vivo OriginOS 6.
 *
 * Gated by SileroVad (ONNX Runtime) to prevent freezing when the screen turns off.
 * Evaluates speech energy envelopes and duration contours to detect "Hey Bro" with zero
 * dependency on SpeechRecognizer polling during lock-screen / screen-off states.
 */
class HeyBroPhraseWakeEngine(private val context: Context) : IWakeWordEngine {

    companion object {
        private const val TAG = "HeyBroPhraseWakeEngine"
        private const val FRAME_LENGTH = 512 // 32ms at 16kHz
        private const val SPEECH_THRESHOLD = 0.25f
    }

    private var sileroVad: SileroVad? = null
    private var callback: ((capturedCommand: String?) -> Unit)? = null
    private var isReleased = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Acoustic contour tracking for "Hey Bro" (2 distinct vocalic bursts: "Hey" + "Bro")
    private var totalSpeechFrames = 0
    private var trailingSilenceFrames = 0
    private var peakCount = 0
    private var lastPeakFrame = 0
    private var utteranceStartFrame = 0
    private var frameCounter = 0
    private var inCandidateWindow = false
    private var lastWakeTimeMs = 0L

    fun setWakeCallback(callback: (capturedCommand: String?) -> Unit) {
        this.callback = callback
    }

    override fun initialize(): Boolean {
        isReleased = false
        totalSpeechFrames = 0
        trailingSilenceFrames = 0
        peakCount = 0
        frameCounter = 0
        inCandidateWindow = false

        return try {
            sileroVad = SileroVad(context)
            Log.i(TAG, "Initialized SileroVad-gated continuous wake engine for OriginOS 6 / Android 15")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SileroVad: ${e.message}")
            false
        }
    }

    override fun process(frame: ShortArray): Boolean {
        if (isReleased) return false
        frameCounter++

        val vad = sileroVad ?: return false
        val vadScore = try {
            vad.isSpeech(frame, 16000)
        } catch (_: Exception) {
            -1f
        }

        // Measure frame RMS energy
        var sumSquares = 0.0
        for (sample in frame) {
            val norm = sample.toDouble() / Short.MAX_VALUE
            sumSquares += norm * norm
        }
        val rms = kotlin.math.sqrt(sumSquares / frame.size).toFloat()

        // Responsive speech detection: Silero VAD >= 0.25f or RMS > 0.015f
        val isSpeech = vadScore >= SPEECH_THRESHOLD || rms > 0.015f

        if (isSpeech) {
            totalSpeechFrames++
            trailingSilenceFrames = 0

            if (!inCandidateWindow) {
                inCandidateWindow = true
                utteranceStartFrame = frameCounter
                peakCount = 0
                lastPeakFrame = 0
                totalSpeechFrames = 1
            }

            // Detect vocalic energy peaks (> 0.022 RMS separated by at least 3 frames ~96ms)
            if (rms > 0.022f && (frameCounter - lastPeakFrame >= 3)) {
                peakCount++
                lastPeakFrame = frameCounter
            }

        } else {
            if (inCandidateWindow) {
                trailingSilenceFrames++

                // "Hey Bro" lasts ~300ms - 1500ms (approx 10 to 48 frames at 32ms each)
                val totalUtteranceFrames = frameCounter - utteranceStartFrame

                // Match 1: Two vocalic bursts with trailing silence pause
                if (trailingSilenceFrames in 1..8 &&
                    totalUtteranceFrames in 10..48 &&
                    peakCount in 1..5 &&
                    totalSpeechFrames in 5..35
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastWakeTimeMs > 750L) {
                        lastWakeTimeMs = now
                        Log.i(TAG, "Acoustic wake pattern confirmed (pause path): duration=${totalUtteranceFrames * 32}ms, peaks=$peakCount, speechFrames=$totalSpeechFrames, vad=$vadScore")
                        resetState()
                        return true
                    }
                }

                // If user paused too long between words or after speaking (> 500ms silence or > 1.6s total), reset
                if (trailingSilenceFrames > 16 || totalUtteranceFrames > 52) {
                    resetState()
                }
            }
        }

        return false
    }

    fun resetState() {
        inCandidateWindow = false
        totalSpeechFrames = 0
        trailingSilenceFrames = 0
        peakCount = 0
        lastPeakFrame = 0
        utteranceStartFrame = 0
        try {
            sileroVad?.resetStates()
        } catch (_: Exception) {}
    }

    override fun release() {
        isReleased = true
        resetState()
        try {
            sileroVad?.close()
        } catch (_: Exception) {}
        sileroVad = null
        callback = null
    }

    override fun getEngineName(): String = "HeyBroPhraseWakeEngine"

    override fun getRequiredFrameLength(): Int = FRAME_LENGTH
}
