package com.example.hey_bro

import android.content.Context
import android.util.Log

class WakeWordManager(context: Context, picovoiceKey: String = "") {

    private val TAG = "WakeWordManager"

    // Primary and only continuous wake phrase engine
    private val engines: List<IWakeWordEngine> = listOf(
        HeyBroPhraseWakeEngine(context)
    )

    var activeEngine: IWakeWordEngine? = null
        private set

    fun initialize(): Boolean {
        for (engine in engines) {
            Log.d(TAG, "Attempting to initialize ${engine.getEngineName()}...")
            if (engine.initialize()) {
                activeEngine = engine
                Log.i(TAG, "Successfully activated engine: ${engine.getEngineName()}")
                return true
            } else {
                Log.w(TAG, "${engine.getEngineName()} failed to initialize, falling back to next engine...")
            }
        }
        
        Log.e(TAG, "CRITICAL ERROR: Wake word engine failed to initialize!")
        return false
    }

    fun process(frame: ShortArray): Boolean {
        return activeEngine?.process(frame) ?: false
    }

    fun getRequiredFrameLength(): Int {
        return activeEngine?.getRequiredFrameLength() ?: 512
    }

    fun getActiveEngineName(): String {
        return activeEngine?.getEngineName() ?: "None (Failed)"
    }

    fun release() {
        activeEngine?.release()
        activeEngine = null
    }
}
