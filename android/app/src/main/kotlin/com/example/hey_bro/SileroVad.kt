package com.example.hey_bro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

class SileroVad(context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    // VAD state buffers (v5 uses a single state tensor instead of h and c)
    private var state = Array(2) { Array(1) { FloatArray(128) { 0f } } }
    
    init {
        ortEnv = OrtEnvironment.getEnvironment()
        // Note: In production, silero_vad.onnx must be placed in the assets folder
        // and copied to the internal storage or read directly.
        try {
            val assetManager = context.assets
            val modelStream = assetManager.open("silero_vad.onnx")
            val modelBytes = modelStream.readBytes()
            ortSession = ortEnv?.createSession(modelBytes, OrtSession.SessionOptions())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isSpeech(audioData: ShortArray, sampleRate: Int = 16000): Float {
        if (ortSession == null) return 0f

        val floats = FloatArray(audioData.size) { audioData[it] / 32768f }
        val audioTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floats), longArrayOf(1, floats.size.toLong()))
        val srTensor = OnnxTensor.createTensor(ortEnv, longArrayOf(sampleRate.toLong()))
        
        val stateTensor = OnnxTensor.createTensor(ortEnv, state)

        val inputs = mapOf(
            "input" to audioTensor,
            "sr" to srTensor,
            "state" to stateTensor
        )

        try {
            val result = ortSession?.run(inputs)
            if (result != null) {
                val out = result[0].value as Array<FloatArray>
                val stateOut = result[1].value as Array<Array<FloatArray>>
                
                state = stateOut
                
                return out[0][0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioTensor.close()
            srTensor.close()
            stateTensor.close()
        }
        return 0f
    }
    
    fun resetStates() {
        state = Array(2) { Array(1) { FloatArray(128) { 0f } } }
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
    }
}
