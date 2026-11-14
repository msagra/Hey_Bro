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
        private const val SPEECH_THRESHOLD = 0.35f
    }

    private var sileroVad: SileroVad? = null
    private var callback: ((capturedCommand: String?) -> Unit)? = null
    private var isReleased = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Acoustic contour tracking for "Hey Bro" (2 distinct vocalic bursts: "Hey" + "Bro")
    private var consecutiveSpeechFrames = 0
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
        consecutiveSpeechFrames = 0
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

        val isSpeech = vadScore >= SPEECH_THRESHOLD || rms > 0.035f

        if (isSpeech) {
            consecutiveSpeechFrames++
            trailingSilenceFrames = 0

            if (!inCandidateWindow) {
                inCandidateWindow = true
                utteranceStartFrame = frameCounter
                peakCount = 0
                lastPeakFrame = 0
            }

            // Detect vocalic energy peaks (> 0.05 RMS separated by at least 4 frames ~128ms)
            if (rms > 0.05f && (frameCounter - lastPeakFrame >= 4)) {
                peakCount++
                lastPeakFrame = frameCounter
            }

        } else {
            if (inCandidateWindow) {
                trailingSilenceFrames++

                // "Hey Bro" lasts ~450ms - 1300ms (approx 14 to 42 frames at 32ms each)
                val totalUtteranceFrames = frameCounter - utteranceStartFrame

                if (trailingSilenceFrames in 3..10 &&
                    totalUtteranceFrames in 14..45 &&
                    peakCount in 1..4 &&
                    consecutiveSpeechFrames >= 8
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastWakeTimeMs > 1200L) {
                        lastWakeTimeMs = now
                        Log.i(TAG, "Acoustic wake pattern confirmed: duration=${totalUtteranceFrames * 32}ms, peaks=$peakCount, vad=$vadScore")
                        resetState()
                        mainHandler.post {
                            callback?.invoke(null)
                        }
                        return true
                    }
                }

                if (trailingSilenceFrames > 12 || totalUtteranceFrames > 50) {
                    resetState()
                }
            }
        }

        return false
    }

    fun resetState() {
        inCandidateWindow = false
        consecutiveSpeechFrames = 0
        trailingSilenceFrames = 0
        peakCount = 0
        utteranceStartFrame = 0
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
