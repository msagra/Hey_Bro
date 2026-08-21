package com.example.hey_bro

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import android.util.Log

class PicovoiceEngine(
    private val context: Context,
    private val accessKey: String
) : IWakeWordEngine {

    private var porcupine: Porcupine? = null

    override fun initialize(): Boolean {
        if (accessKey.isBlank() || accessKey.contains("YOUR_PICOVOICE")) {
            Log.w("PicovoiceEngine", "Invalid or missing Picovoice Access Key.")
            return false
        }
        
        return try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE) // Reverted to default while waiting for account approval
                .build(context)
            Log.i("PicovoiceEngine", "Picovoice initialized successfully with default keyword.")
            true
        } catch (e: Exception) {
            Log.e("PicovoiceEngine", "Failed to initialize: ${e.message}")
            false
        }
    }

    override fun process(frame: ShortArray): Boolean {
        if (frame.size != getRequiredFrameLength()) return false
        return try {
            val index = porcupine?.process(frame) ?: -1
            index >= 0
        } catch (e: Exception) {
            false
        }
    }

    override fun release() {
        porcupine?.delete()
        porcupine = null
    }

    override fun getEngineName(): String = "Picovoice Porcupine"

    override fun getRequiredFrameLength(): Int = porcupine?.frameLength ?: 512
}
