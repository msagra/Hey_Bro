package com.example.hey_bro

import android.content.Context
import android.util.Log
import kotlin.math.abs

class SimpleAudioWakeTrigger(private val context: Context) : IWakeWordEngine {
    private var triggered = false
    private val threshold = 2600.0f
    private val minBurstMs = 900L
    private var lastPeak = 0f
    private var burstStartMs = 0L

    override fun initialize(): Boolean {
        Log.i("SimpleAudioWakeTrigger", "Using lightweight audio energy wake trigger")
        return true
    }

    override fun process(frame: ShortArray): Boolean {
        var rms = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            rms += value * value
        }
        rms = Math.sqrt(rms / frame.size.coerceAtLeast(1))

        val absPeak = frame.fold(0f) { max, sample -> max.coerceAtLeast(abs(sample.toFloat())) }
        val now = System.currentTimeMillis()

        if (absPeak > threshold) {
            if (burstStartMs == 0L) {
                burstStartMs = now
            }
            lastPeak = absPeak
        } else if (burstStartMs != 0L && now - burstStartMs >= minBurstMs) {
            triggered = true
            burstStartMs = 0L
            lastPeak = 0f
            return true
        } else {
            burstStartMs = 0L
            lastPeak = 0f
        }

        if (triggered) {
            triggered = false
            return true
        }

        return false
    }

    override fun release() {
        triggered = false
        burstStartMs = 0L
        lastPeak = 0f
    }

    override fun getEngineName(): String = "SimpleAudioWakeTrigger"

    override fun getRequiredFrameLength(): Int = 512
}
