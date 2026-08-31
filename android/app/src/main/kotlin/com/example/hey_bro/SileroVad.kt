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
    
    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val assetManager = context.assets
            val modelStream = assetManager.open("silero_vad.onnx")
            val modelBytes = modelStream.readBytes()
            ortSession = ortEnv?.createSession(modelBytes, OrtSession.SessionOptions())
        } catch (e: Exception) {
            android.util.Log.e("SileroVad", "Init failed: ${e.message}")
            disabled = true
        }
    }

    fun isSpeech(audioData: ShortArray, sampleRate: Int = 16000): Float {
        if (disabled || audioData.isEmpty()) return -1f
        
        sessionLock.withLock {
            val session = ortSession ?: return -1f
            val env = ortEnv ?: return -1f

            var audioTensor: OnnxTensor? = null
            var srTensor: OnnxTensor? = null
            var stateTensor: OnnxTensor? = null
            var result: OrtSession.Result? = null

            return try {
                val floats = FloatArray(audioData.size) { audioData[it] / 32768f }
                audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), longArrayOf(1, floats.size.toLong()))
                srTensor = OnnxTensor.createTensor(env, longArrayOf(sampleRate.toLong()))
                stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))

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
                    val probability = if (outputBuffer.hasRemaining()) outputBuffer.get() else 0f
                    stateBuffer.rewind()
                    stateBuffer.get(state)
                    probability.coerceIn(0f, 1f)
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
                srTensor?.close()
                stateTensor?.close()
            }
        }
    }
    
    fun resetStates() {
        state.fill(0f)
    }

    fun close() {
        disabled = true
        sessionLock.withLock {
            val session = ortSession
            ortSession = null
            try { session?.close() } catch (_: Exception) {}
            try { ortEnv?.close() } catch (_: Exception) {}
            ortEnv = null
        }
    }
}
