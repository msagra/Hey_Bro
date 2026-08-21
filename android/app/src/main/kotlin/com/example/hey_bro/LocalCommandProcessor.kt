package com.example.hey_bro

import android.util.Log
import org.json.JSONObject
import java.util.Calendar

/**
 * Fully local, zero-network command parser.
 *
 * Takes a raw transcript string (from on-device STT) and returns a JSON
 * action payload that DeviceOperatorService can execute directly, OR
 * a spoken reply string that the TTS engine should speak.
 */
object LocalCommandProcessor {

    private const val TAG = "LocalCommandProcessor"
    private var geminiBridge: GeminiIntentBridge? = null

    fun initialize(context: android.content.Context) {
        VoiceLearningStore.initialize(context)
        geminiBridge = GeminiIntentBridge(context)
    }

    fun interpretWithGemini(transcript: String): Result? {
        return geminiBridge?.interpret(transcript)
    }

    data class Result(
        val action: JSONObject?,   // non-null → execute a device action
        val reply: String          // always set – what to speak back
    )

    // ─────────────────────────────────────────────────────────────────────────
    fun process(transcript: String): Result {
        val t = transcript.trim().lowercase()
        Log.d(TAG, "Processing: \"$t\"")

        val learnedAction = VoiceLearningStore.findActionForTranscript(t)
        if (learnedAction != null) {
            return actionResultFromName(learnedAction)
        }

        val result = when {

            // ── Basic lock-screen-safe commands ──────────────────────────────
            t.containsAny("set timer for", "start timer", "timer for") -> {
                val seconds = extractDurationSeconds(t)
                Result(
                    JSONObject().apply {
                        put("action", "setTimer")
                        put("seconds", seconds)
                    },
                    "Timer set for ${formatDuration(seconds)}"
                )
            }

            t.containsAny("set alarm", "set an alarm", "wake me up", "alarm for") -> {
                val (h, m) = extractTime(t)
                val label  = extractAlarmLabel(t)
                Result(
                    JSONObject().apply {
                        put("action", "setAlarm")
                        put("hour", h)
                        put("minute", m)
                        put("message", label)
                    },
                    "Alarm set for ${formatTime(h, m)}"
                )
            }

            t.containsAny("open camera", "launch camera", "camera on") ->
                Result(action("openCamera"), "Opening camera")

            t.containsAny("take a photo", "take photo", "click a photo", "capture photo",
                "take a picture", "take picture", "click picture") -> {
                val delay = extractDelay(t)
                Result(
                    JSONObject().apply {
                        put("action", "takePhoto")
                        put("delaySeconds", delay)
                    },
                    if (delay > 0) "Taking a photo in $delay seconds" else "Taking a photo"
                )
            }

            t.containsAny("take a selfie", "click selfie", "front camera selfie") -> {
                val delay = extractDelay(t)
                Result(
                    JSONObject().apply {
                        put("action", "takeSelfie")
                        put("delaySeconds", delay)
                    },
                    if (delay > 0) "Taking a selfie in $delay seconds" else "Taking a selfie"
                )
            }

            t.containsAny("call ", "dial ", "phone ") && !t.containsAny("call me", "phone me") -> {
                val contact = extractCallTarget(t)
                Result(
                    JSONObject().apply {
                        put("action", "makeCall")
                        put("number", contact)
                    },
                    "Calling $contact"
                )
            }

            t.containsAny(
                "turn on flashlight",
                "flashlight on",
                "turn on torch",
                "torch on",
                "turn on flash",
                "flash on",
                "turn on light",
                "light on"
            ) ->
                Result(action("flashlightOn"), "Turning on flashlight")

            t.containsAny(
                "turn off flashlight",
                "flashlight off",
                "turn off torch",
                "torch off",
                "turn off flash",
                "flash off",
                "turn off light",
                "light off"
            ) ->
                Result(action("flashlightOff"), "Turning off flashlight")

            t.containsAny("hotspot on", "turn on hotspot", "enable hotspot") ->
                Result(action("toggleHotspot", "enabled" to "true"), "Turning on hotspot")

            t.containsAny("hotspot off", "turn off hotspot", "disable hotspot") ->
                Result(action("toggleHotspot", "enabled" to "false"), "Turning off hotspot")

            t.containsAny("open settings", "device settings", "settings") ->
                Result(action("openSettings", "setting" to "main"), "Opening settings")

            t.containsAny("volume up", "increase volume", "louder") ->
                Result(action("volumeUp"), "Increasing volume")

            t.containsAny("volume down", "decrease volume", "lower volume", "quieter") ->
                Result(action("volumeDown"), "Decreasing volume")

            t.containsAny("mute", "silence") ->
                Result(action("mute"), "Muted")

            t.containsAny("what time", "current time", "what's the time") -> {
                val cal = Calendar.getInstance()
                val reply = "It's ${formatTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}"
                Result(null, reply)
            }

            t.containsAny("what date", "today's date", "what day") -> {
                val cal = Calendar.getInstance()
                val days = listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")
                val months = listOf("January","February","March","April","May","June","July","August","September","October","November","December")
                val reply = "Today is ${days[cal.get(Calendar.DAY_OF_WEEK)-1]}, ${months[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.DAY_OF_MONTH)}"
                Result(null, reply)
            }

            // ── Unlocked-only commands ────────────────────────────────────────
            t.containsAny("open whatsapp", "open what's app", "launch whatsapp") ->
                Result(action("openApp", "appName" to "WhatsApp"), "Opening WhatsApp")

            t.matchesSend("whatsapp") -> {
                val (contact, msg) = extractContactAndMessage(t)
                Result(
                    JSONObject().apply {
                        put("action", "sendWhatsApp")
                        put("contact", contact)
                        put("message", msg)
                    },
                    if (contact.isNotBlank()) "Sending WhatsApp message to $contact"
                    else "Opening WhatsApp to send a message"
                )
            }

            t.startsWith("open ") || t.startsWith("launch ") -> {
                val appName = extractAppName(t)
                Result(action("openApp", "appName" to appName), "Opening $appName")
            }

            t.containsAny("search for", "search ", "google ", "look up", "find ") -> {
                val query = extractSearchQuery(t)
                Result(action("searchWeb", "query" to query), "Searching for $query")
            }

            t.containsAny("navigate to", "directions to", "maps to", "open maps") -> {
                val query = extractLocationQuery(t)
                Result(action("openMaps", "query" to query), if (query.isBlank()) "Opening Maps" else "Opening directions to $query")
            }

            t.containsAny("play on youtube", "youtube ") -> {
                val query = extractMediaQuery(t, "youtube")
                Result(
                    JSONObject().apply {
                        put("action", "playMedia"); put("query", query); put("app", "YouTube")
                    },
                    "Playing $query on YouTube"
                )
            }

            t.containsAny("play on spotify", "spotify ") -> {
                val query = extractMediaQuery(t, "spotify")
                Result(
                    JSONObject().apply {
                        put("action", "playMedia"); put("query", query); put("app", "Spotify")
                    },
                    "Playing $query on Spotify"
                )
            }

            t.startsWith("play ") -> {
                val query = t.removePrefix("play ").trim()
                Result(
                    JSONObject().apply {
                        put("action", "playMedia"); put("query", query); put("app", "YouTube")
                    },
                    "Playing $query"
                )
            }

            t.containsAny("go back", "press back", "back button") ->
                Result(action("globalBack"), "Going back")

            t.containsAny("go home", "press home", "home screen") ->
                Result(action("globalHome"), "Going to home screen")

            t.containsAny("take screenshot", "screenshot", "capture screen") ->
                Result(action("screenshot"), "Taking a screenshot")

            t.containsAny("scroll down", "scroll up") -> {
                val up = t.contains("scroll up")
                Result(action(if (up) "scrollUp" else "scrollDown"), if (up) "Scrolling up" else "Scrolling down")
            }

            t.containsAny("open wifi", "wi-fi settings", "wifi settings") ->
                Result(action("openSettings", "setting" to "wifi"), "Opening WiFi settings")

            t.containsAny("open bluetooth", "bluetooth settings") ->
                Result(action("openSettings", "setting" to "bluetooth"), "Opening Bluetooth settings")

            t.containsAny(
                "turn on wifi",
                "enable wifi",
                "wifi on",
                "turn wifi on",
                "wi fi on",
                "wifi enabled"
            ) ->
                Result(action("toggleWifi", "enabled" to "true"), "Turning on WiFi")
 
            t.containsAny(
                "turn off wifi",
                "disable wifi",
                "wifi off",
                "turn wifi off",
                "wi fi off",
                "wifi disabled"
            ) ->
                Result(action("toggleWifi", "enabled" to "false"), "Turning off WiFi")
 
            t.containsAny(
                "turn on bluetooth",
                "enable bluetooth",
                "bluetooth on",
                "turn bluetooth on",
                "blue tooth on"
            ) ->
                Result(action("toggleBluetooth", "enabled" to "true"), "Turning on Bluetooth")
 
            t.containsAny(
                "turn off bluetooth",
                "disable bluetooth",
                "bluetooth off",
                "turn bluetooth off",
                "blue tooth off"
            ) ->
                Result(action("toggleBluetooth", "enabled" to "false"), "Turning off Bluetooth")
 
            t.containsAny("airplane mode on", "turn on airplane mode") ->
                Result(action("toggleAirplaneMode", "enabled" to "true"), "Turning on Airplane mode")
 
            t.containsAny("airplane mode off", "turn off airplane mode") ->
                Result(action("toggleAirplaneMode", "enabled" to "false"), "Turning off Airplane mode")

            t.containsAny("brightness up", "increase brightness") ->
                Result(action("adjustBrightness", "direction" to "up"), "Increasing brightness")

            t.containsAny("brightness down", "decrease brightness") ->
                Result(action("adjustBrightness", "direction" to "down"), "Decreasing brightness")

            // ── General / social / utility ─────────────────────────────────────
            t.containsAny("hello", "hi bro", "hey there", "what's up") ->
                Result(null, "Hey! I'm ready. What do you need?")

            t.containsAny("who are you", "what are you", "your name") ->
                Result(null, "I'm Hey Bro, your local AI assistant. I run entirely on your device, no internet needed.")

            t.containsAny("thank you", "thanks") ->
                Result(null, "Anytime! I'm here when you need me.")

            t.containsAny("how are you", "how you doing") ->
                Result(null, "I'm doing great, ready to help you out.")

            t.containsAny("tell me a joke", "say a joke", "joke") ->
                Result(null, jokes.random())

            t.containsAny("stop listening", "go to sleep", "goodbye", "bye bro") ->
                Result(action("sleep"), "Going to sleep. Say Hey Bro to wake me up.")

            else -> {
                Log.d(TAG, "No rule matched for: \"$t\"")
                Result(null, "Sorry, I didn't understand \"$transcript\". Try saying something like: set alarm, call mom, turn on flashlight, open WhatsApp, search for news, or play music.")
            }
        }

        if (result.action != null) {
            val actionName = result.action.optString("action", "")
            if (actionName.isNotBlank()) {
                VoiceLearningStore.learn(transcript, actionName)
            }
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun actionResultFromName(actionName: String): Result {
        return when (actionName) {
            "flashlightOn" -> Result(action("flashlightOn"), "Turning on flashlight")
            "flashlightOff" -> Result(action("flashlightOff"), "Turning off flashlight")
            "toggleWifi" -> Result(action("toggleWifi", "enabled" to "true"), "Turning on WiFi")
            "toggleBluetooth" -> Result(action("toggleBluetooth", "enabled" to "true"), "Turning on Bluetooth")
            "toggleAirplaneMode" -> Result(action("toggleAirplaneMode", "enabled" to "true"), "Turning on Airplane mode")
            "setAlarm" -> Result(action("setAlarm", "hour" to 7, "minute" to 0, "message" to "Wake up"), "Alarm set for 7:00")
            "makeCall" -> Result(action("makeCall", "number" to ""), "Calling")
            "openApp" -> Result(action("openApp", "appName" to "Main app"), "Opening app")
            "playMedia" -> Result(action("playMedia", "query" to "", "app" to "YouTube"), "Playing media")
            else -> Result(null, "I’m ready to help.")
        }
    }

    private fun action(act: String, vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply {
            put("action", act)
            pairs.forEach { (k, v) -> put(k, v) }
        }

    private fun String.containsAny(vararg tokens: String) = tokens.any { this.contains(it) }

    private fun String.matchesSend(platform: String) =
        (this.contains("send") || this.contains("message")) && this.contains(platform)

    private fun extractAppName(t: String): String =
        t.removePrefix("open ").removePrefix("launch ").trim()
         .replaceFirstChar { it.uppercaseChar() }

    private fun extractSearchQuery(t: String): String {
        val triggers = listOf("search for ", "search ", "google ", "look up ", "find ")
        for (trigger in triggers) {
            val idx = t.indexOf(trigger)
            if (idx >= 0) return t.substring(idx + trigger.length).trim()
        }
        return t
    }

    private fun extractMediaQuery(t: String, app: String): String {
        val markers = listOf("on $app", "$app ", "play ")
        var q = t
        for (m in markers) q = q.replace(m, "")
        return q.trim()
    }

    private fun extractCallTarget(t: String): String {
        val triggers = listOf("call ", "dial ", "phone ")
        for (trigger in triggers) {
            val idx = t.indexOf(trigger)
            if (idx >= 0) return t.substring(idx + trigger.length).trim()
        }
        return t
    }

    private fun extractDurationSeconds(t: String): Int {
        val r = Regex("(?:for|in)\\s*(\\d+)\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)")
        val match = r.find(t)
        if (match != null) {
            val value = match.groupValues[1].toInt()
            val unit = match.groupValues[0].substringAfterLast(" ").lowercase()
            return when {
                unit.startsWith("minute") -> value * 60
                unit.startsWith("hour") -> value * 60 * 60
                else -> value
            }
        }
        val quick = Regex("(\\d+)\\s*(?:seconds?|secs?)").find(t)
        return quick?.groupValues?.get(1)?.toIntOrNull() ?: 30
    }

    private fun formatDuration(seconds: Int): String {
        val totalMinutes = seconds / 60
        val remainingSeconds = seconds % 60
        val totalHours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60
        return when {
            totalHours > 0 -> "${totalHours}h ${remainingMinutes}m ${remainingSeconds}s"
            totalMinutes > 0 -> "${totalMinutes}m ${remainingSeconds}s"
            else -> "${seconds}s"
        }
    }

    private fun extractLocationQuery(t: String): String {
        val markers = listOf("navigate to ", "directions to ", "maps to ", "open maps")
        for (marker in markers) {
            val idx = t.indexOf(marker)
            if (idx >= 0) {
                val query = t.substring(idx + marker.length).trim()
                if (query.isNotBlank()) return query
            }
        }
        return ""
    }

    private fun extractContactAndMessage(t: String): Pair<String, String> {
        // "send a message to John saying hello there"
        // "message John on whatsapp hello"
        val toIdx   = t.indexOf(" to ")
        val sayIdx  = t.lastIndexOf(" saying ")
        val contact = when {
            toIdx >= 0 && sayIdx > toIdx -> t.substring(toIdx + 4, sayIdx).trim()
            toIdx >= 0                   -> t.substring(toIdx + 4).trim()
            else                         -> ""
        }
        val message = if (sayIdx >= 0) t.substring(sayIdx + 8).trim() else ""
        return contact to message
    }

    /**
     * Extracts "at X o'clock" / "at H:MM" / "at H am/pm" patterns.
     * Returns 24-hour (hour, minute).
     */
    private fun extractTime(t: String): Pair<Int, Int> {
        // Pattern: "at 7:30", "at 7 30", "at 7:30 am", "at 7:30 pm"
        val r1 = Regex("""at (\d{1,2})[: ](\d{2})\s*(am|pm)?""")
        r1.find(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[3]
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            return h to min
        }
        // Pattern: "at 7 am", "at 7 pm", "at 7 o'clock"
        val r2 = Regex("""at (\d{1,2})\s*(am|pm|o'clock)?""")
        r2.find(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            val ampm = m.groupValues[2]
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            return h to 0
        }
        return 7 to 0 // sensible default
    }

    private fun extractAlarmLabel(t: String): String {
        val r = Regex("""(for|label|named?)\s+(.+)$""")
        return r.find(t)?.groupValues?.get(2)?.trim() ?: ""
    }

    /** Extracts "after X seconds / in X seconds / X seconds" */
    private fun extractDelay(t: String): Int {
        val r = Regex("""(\d+)\s+second""")
        return r.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun formatTime(h: Int, m: Int): String {
        val suffix = if (h < 12) "AM" else "PM"
        val h12    = when {
            h == 0  -> 12
            h > 12  -> h - 12
            else    -> h
        }
        return "$h12:${m.toString().padStart(2, '0')} $suffix"
    }

    private val jokes = listOf(
        "Why don't scientists trust atoms? Because they make up everything!",
        "I told my phone I needed a break. Now it won't stop sending me notifications about it.",
        "Why did the smartphone go to school? To improve its app-titude!",
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "What do you call a fish without eyes? A fsh!",
        "Why did the computer go to the doctor? It had a virus and kept crashing!",
        "I'm reading a book about anti-gravity. It's impossible to put down.",
        "Why was the math book sad? Because it had too many problems."
    )
}
