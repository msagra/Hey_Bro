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
        seedDefaultsIfEmpty()
    }

    private fun seedDefaultsIfEmpty() {
        if (loadMap().isNotEmpty()) return
        val defaults = mapOf(
            "torch" to "flashlightOn",
            "turn on flashlight" to "flashlightOn",
            "turn off flashlight" to "flashlightOff",
            "torch off" to "flashlightOff",
            "silence phone" to "mute",
            "mute" to "mute",
            "volume up" to "volumeUp",
            "volume down" to "volumeDown",
            "take a selfie" to "takeSelfie",
            "front camera" to "takeSelfie",
            "take photo" to "takePhoto",
            "open camera" to "openCamera",
            "screenshot" to "screenshot",
            "take screenshot" to "screenshot",
            "lock screen" to "lockScreen",
            "battery saver" to "batterySaver",
            "do not disturb" to "doNotDisturb",
            "show notifications" to "notifications",
            "quick settings" to "quickSettings",
            "recent apps" to "recents",
            "go home" to "globalHome",
            "go back" to "globalBack",
            "pause music" to "mediaPlayPause",
            "next song" to "mediaNext",
            "previous song" to "mediaPrevious",
            "turn on wifi" to "toggleWifi",
            "turn on bluetooth" to "toggleBluetooth",
            "open maps" to "openMaps",
            "open whatsapp" to "openApp:com.whatsapp",
            "open youtube" to "openApp:com.google.android.youtube"
        )
        prefs?.edit()?.putString(ALIAS_KEY, JSONObject(defaults).toString())?.apply()
        Log.i(TAG, "Seeded ${defaults.size} default voice shortcuts into VoiceLearningStore")
    }

    fun learn(transcript: String, action: String) {
        val normalized = normalize(transcript)
        val baseAction = if (action.startsWith("openApp:")) "openApp" else action
        if (normalized.isBlank() || baseAction !in supportedActions) return
        val current = loadMap().toMutableMap()
        current[normalized] = action
        prefs?.edit()?.putString(ALIAS_KEY, JSONObject(current).toString())?.apply()
        Log.d(TAG, "Learned alias: '$normalized' -> '$action'")
    }

    fun learnBatch(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val current = loadMap().toMutableMap()
        for ((phrase, action) in entries) {
            val normalized = normalize(phrase)
            val baseAction = if (action.startsWith("openApp:")) "openApp" else action
            if (normalized.isNotBlank() && baseAction in supportedActions) {
                current[normalized] = action
            }
        }
        prefs?.edit()?.putString(ALIAS_KEY, JSONObject(current).toString())?.apply()
        Log.d(TAG, "Learned batch of ${entries.size} aliases")
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
