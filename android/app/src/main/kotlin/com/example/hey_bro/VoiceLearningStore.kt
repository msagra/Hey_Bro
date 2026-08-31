package com.example.hey_bro

import android.content.Context
import android.util.Log
import org.json.JSONObject

object VoiceLearningStore {
    private const val TAG = "VoiceLearningStore"
    private const val PREF_NAME = "hey_bro_learning"
    private const val ALIAS_KEY = "custom_aliases"
    private var prefs: android.content.SharedPreferences? = null
    private val supportedActions = setOf(
        "openApp", "openCamera", "takePhoto", "takeSelfie", "setAlarm", "setTimer",
        "makeCall", "sendWhatsApp", "searchWeb", "openMaps", "playMedia", "screenshot",
        "scrollDown", "scrollUp", "volumeUp", "volumeDown", "mute", "flashlightOn",
        "flashlightOff", "toggleWifi", "toggleBluetooth", "toggleAirplaneMode",
        "toggleHotspot", "adjustBrightness", "openSettings", "lockScreen", "notifications",
        "quickSettings", "recents", "mediaPlayPause", "mediaNext", "mediaPrevious",
        "batterySaver", "doNotDisturb", "autoRotate", "createCalendarEvent", "sendSms",
        "doubleTap", "longPress", "swipeLeft", "swipeRight", "globalBack", "globalHome",
        "sleep"
    )

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun learn(transcript: String, action: String) {
        val normalized = normalize(transcript)
        if (normalized.isBlank() || action !in supportedActions) return
        val current = loadMap().toMutableMap()
        current[normalized] = action
        prefs?.edit()?.putString(ALIAS_KEY, JSONObject(current).toString())?.apply()
        Log.d(TAG, "Learned alias: '$normalized' -> '$action'")
    }

    fun findActionForTranscript(transcript: String): String? {
        val normalized = normalize(transcript)
        if (normalized.isBlank()) return null
        val mapping = loadMap()
        for ((alias, action) in mapping) {
            if (normalized == alias || normalized.contains(alias) || alias.contains(normalized) ||
                tokenSimilarity(normalized, alias) >= 0.82f) {
                return action
            }
        }
        return null
    }

    private fun tokenSimilarity(left: String, right: String): Float {
        val a = left.split(' ').filter { it.length > 1 }.toSet()
        val b = right.split(' ').filter { it.length > 1 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0f
        return (a.intersect(b).size.toFloat() / maxOf(a.size, b.size).toFloat())
    }

    private fun loadMap(): Map<String, String> {
        val raw = prefs?.getString(ALIAS_KEY, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotBlank()) result[key] = value
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse learned aliases", e)
            emptyMap()
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
