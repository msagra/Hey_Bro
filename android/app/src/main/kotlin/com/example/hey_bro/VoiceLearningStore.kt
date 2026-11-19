package com.example.hey_bro

import android.content.Context
import android.util.Log
import org.json.JSONObject

object VoiceLearningStore {
    private const val TAG = "VoiceLearningStore"
    private const val PREF_NAME = "hey_bro_learning"
    private const val ALIAS_KEY = "custom_aliases"
    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var cachedAliases: Map<String, String>? = null
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

    @Synchronized
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        cachedAliases = null
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
        saveMap(defaults)
        Log.i(TAG, "Seeded ${defaults.size} default voice shortcuts into VoiceLearningStore")
    }

    @Synchronized
    fun learn(transcript: String, action: String) {
        val normalized = normalize(transcript)
        val baseAction = if (action.startsWith("openApp:")) "openApp" else action.split(":").first()
        if (normalized.isBlank() || (baseAction !in supportedActions && !action.contains(":"))) return
        val current = loadMap().toMutableMap()
        current[normalized] = action
        saveMap(current)
        Log.d(TAG, "Learned a voice shortcut")
    }

    @Synchronized
    fun learnBatch(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val current = loadMap().toMutableMap()
        for ((phrase, action) in entries) {
            val normalized = normalize(phrase)
            val baseAction = if (action.startsWith("openApp:")) "openApp" else action.split(":").first()
            if (normalized.isNotBlank() && (baseAction in supportedActions || action.contains(":"))) {
                current[normalized] = action
            }
        }
        saveMap(current)
        Log.d(TAG, "Learned ${entries.size} voice shortcuts")
    }

    @Synchronized
    fun remove(transcript: String) {
        val normalized = normalize(transcript)
        val current = loadMap().toMutableMap()
        if (current.remove(normalized) != null) {
            saveMap(current)
            Log.d(TAG, "Removed a voice shortcut")
        }
    }

    @Synchronized
    fun replaceAll(entries: Map<String, String>) {
        val current = linkedMapOf<String, String>()
        for ((phrase, action) in entries) {
            val normalized = normalize(phrase)
            val baseAction = if (action.startsWith("openApp:")) "openApp" else action.split(":").first()
            if (normalized.isNotBlank() && (baseAction in supportedActions || action.contains(":"))) {
                current[normalized] = action
            }
        }
        saveMap(current)
        Log.d(TAG, "Replaced all learned aliases (${current.size} entries)")
    }

    @Synchronized
    fun findActionForTranscript(transcript: String): String? {
        val normalized = normalize(transcript)
        if (normalized.isBlank()) return null
        val mapping = loadMap()
        for ((alias, action) in mapping) {
            val phraseMatch = Regex("(?:^|\\s)${Regex.escape(alias)}(?:$|\\s)").containsMatchIn(normalized)
            if (normalized == alias || phraseMatch ||
                (normalized.length >= 8 && tokenSimilarity(normalized, alias) >= 0.82f)) {
                return action
            }
        }
        return null
    }

    @Synchronized
    fun findExactActionForTranscript(transcript: String): String? {
        return loadMap()[normalize(transcript)]
    }

    private fun tokenSimilarity(left: String, right: String): Float {
        val a = left.split(' ').filter { it.length > 1 }.toSet()
        val b = right.split(' ').filter { it.length > 1 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0f
        return (a.intersect(b).size.toFloat() / maxOf(a.size, b.size).toFloat())
    }

    private fun loadMap(): Map<String, String> {
        cachedAliases?.let { return it }
        val raw = prefs?.getString(ALIAS_KEY, "") ?: ""
        if (raw.isBlank()) return emptyMap<String, String>().also { cachedAliases = it }
        return try {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotBlank()) result[key] = value
            }
            result.toMap().also { cachedAliases = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse learned aliases", e)
            emptyMap()
        }
    }

    @Synchronized
    private fun saveMap(entries: Map<String, String>) {
        val snapshot = entries.toMap()
        prefs?.edit()?.putString(ALIAS_KEY, JSONObject(snapshot).toString())?.apply()
        cachedAliases = snapshot
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
