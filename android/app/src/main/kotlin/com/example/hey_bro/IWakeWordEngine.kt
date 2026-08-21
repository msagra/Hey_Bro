package com.example.hey_bro

interface IWakeWordEngine {
    fun initialize(): Boolean
    fun process(frame: ShortArray): Boolean
    fun release()
    fun getEngineName(): String
    fun getRequiredFrameLength(): Int
}
