package com.example.hey_bro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SileroVad(context: Context) {

    private var ortEnv: OrtEnvironment? = null
    @Volatile private var ortSession: OrtSession? = null
    private val sessionLock = ReentrantLock()
    
    // Silero expects one flat [2, 1, 128] state tensor for a single stream.
    private var state = FloatArray(2 * 128)
    @Volatile private var disabled = false
    private var failureLogged = false

    // ── Pooled buffers to eliminate per-frame allocations ─────────────────────
    // Standard frame size is 512 samples; pre-allocate once and reuse.
    private val pooledFrameSize = 512
    private val pooledFloats = FloatArray(pooledFrameSize)
    private val pooledFloatBuffer = FloatBuffer.wrap(pooledFloats)

    // Sample rate tensor never changes — create once and reuse.
    private var cachedSrTensor: OnnxTensor? = null

    // Stateful hangover smoothing for natural human syllable transitions
    private var hangoverRemaining = 0
    private val defaultHangoverFrames = 5 // ~160ms bridge
    
    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val assetManager = context.assets
            val modelStream = assetManager.open("silero_vad.onnx")
            val modelBytes = modelStream.readBytes()
            ortSession = ortEnv?.createSession(modelBytes, OrtSession.SessionOptions())

            // Pre-create sample rate tensor (never changes)
            ortEnv?.let { env ->
                cachedSrTensor = OnnxTensor.createTensor(env, longArrayOf(16000L))
            }
        } catch (e: Exception) {
            android.util.Log.e("SileroVad", "Init failed: ${e.message}")
            disabled = true
        }
    }

    /**
     * Compute raw speech probability for a 512-sample (or matching) PCM frame.
     * Incorporates adaptive headroom scaling so quiet or distant speech is accurately detected.
     */
    fun isSpeech(audioData: ShortArray, sampleRate: Int = 16000): Float {
        if (disabled || audioData.isEmpty()) return -1f
        
        sessionLock.withLock {
            val session = ortSession ?: return -1f
            val env = ortEnv ?: return -1f

            var audioTensor: OnnxTensor? = null
            var stateTensor: OnnxTensor? = null
            var result: OrtSession.Result? = null
            // Only create a fresh sr tensor if the sample rate differs from cached 16kHz
            var localSrTensor: OnnxTensor? = null

            return try {
                val frameSize = audioData.size

                // Measure peak frame amplitude for soft pre-amplification of distant / low-mic input
                var maxSample = 0
                for (i in 0 until frameSize) {
                    val abs = kotlin.math.abs(audioData[i].toInt())
                    if (abs > maxSample) maxSample = abs
                }

                // If input is very quiet (e.g. peak < 8000), apply smooth soft gain up to 2.2x
                val gainMultiplier = when {
                    maxSample in 1..4000 -> 2.2f
                    maxSample in 4001..8000 -> 1.5f
                    else -> 1.0f
                }

                // Use pooled buffer if frame size matches, otherwise allocate (rare path)
                val floats: FloatArray
                val floatBuf: FloatBuffer
                if (frameSize == pooledFrameSize) {
                    floats = pooledFloats
                    for (i in 0 until frameSize) {
                        val scaled = (audioData[i] / 32768f) * gainMultiplier
                        floats[i] = scaled.coerceIn(-1.0f, 1.0f)
                    }
                    pooledFloatBuffer.rewind()
                    floatBuf = pooledFloatBuffer
                } else {
                    floats = FloatArray(frameSize) {
                        val scaled = (audioData[it] / 32768f) * gainMultiplier
                        scaled.coerceIn(-1.0f, 1.0f)
                    }
                    floatBuf = FloatBuffer.wrap(floats)
                }

                audioTensor = OnnxTensor.createTensor(env, floatBuf, longArrayOf(1, frameSize.toLong()))
                stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))

                // Use cached sr tensor for the common 16kHz case
                val srTensor = if (sampleRate == 16000 && cachedSrTensor != null) {
                    cachedSrTensor!!
                } else {
                    localSrTensor = OnnxTensor.createTensor(env, longArrayOf(sampleRate.toLong()))
                    localSrTensor!!
                }

                val inputs = mapOf(
                    "input" to audioTensor,
                    "sr" to srTensor,
                    "state" to stateTensor
                )

                result = session.run(inputs)
                if (result != null && result.count() >= 2) {
                    val outputTensor = result[0] as OnnxTensor
                    val stateOutputTensor = result[1] as OnnxTensor
                    val outputBuffer = outputTensor.floatBuffer
                    val stateBuffer = stateOutputTensor.floatBuffer
                    val rawProbability = if (outputBuffer.hasRemaining()) outputBuffer.get() else 0f
                    
                    stateBuffer.rewind()
                    stateBuffer.get(state)
                    
                    // Sanitize state to avoid NaN/Infinity propagation
                    for (i in state.indices) {
                        if (state[i].isNaN() || state[i].isInfinite()) {
                            state[i] = 0f
                        }
                    }

                    rawProbability.coerceIn(0f, 1f)
                } else {
                    -1f
                }
            } catch (e: Exception) {
                // If we get an exception indicating the session is closed, don't log it as a failure
                val msg = e.message ?: ""
                if (!msg.contains("closed", ignoreCase = true)) {
                    if (!failureLogged) {
                        failureLogged = true
                        android.util.Log.w("SileroVad", "Inference failure: $msg")
                    }
                    disabled = true
                }
                -1f
            } finally {
                result?.close()
                audioTensor?.close()
                localSrTensor?.close()
                stateTensor?.close()
            }
        }
    }

    /**
     * Determines whether the frame is speech using hysteresis and hangover frames
     * to avoid choppy false-silence cuts between words and syllables.
     */
    fun isSpeechSmoothed(
        audioData: ShortArray,
        threshold: Float = 0.35f,
        sampleRate: Int = 16000
    ): Boolean {
        return smoothProbability(isSpeech(audioData, sampleRate), threshold)
    }

    fun smoothProbability(prob: Float, threshold: Float = 0.35f): Boolean {
        if (prob >= threshold) {
            hangoverRemaining = defaultHangoverFrames
            return true
        } else if (hangoverRemaining > 0) {
            hangoverRemaining--
            return true
        }
        return false
    }
    
    fun resetStates() {
        hangoverRemaining = 0
        state.fill(0f)
    }

    fun close() {
        disabled = true
        sessionLock.withLock {
            val session = ortSession
            ortSession = null
            try { cachedSrTensor?.close() } catch (_: Exception) {}
            cachedSrTensor = null
            try { session?.close() } catch (_: Exception) {}
            try { ortEnv?.close() } catch (_: Exception) {}
            ortEnv = null
        }
    }
}
