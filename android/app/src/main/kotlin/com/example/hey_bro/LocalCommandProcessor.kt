package com.example.hey_bro

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Fully local, zero-network command parser.
 *
 * Takes a raw transcript string (from on-device STT or backend) and returns a JSON
 * action payload that DeviceOperatorService can execute directly, OR
 * a spoken reply string that the TTS engine should speak.
 */
object LocalCommandProcessor {

    private const val TAG = "LocalCommandProcessor"
    private var geminiBridge: GeminiIntentBridge? = null
    private var groqBridge: GroqIntentBridge? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        VoiceLearningStore.initialize(context)
        geminiBridge = GeminiIntentBridge(context)
        groqBridge = GroqIntentBridge(context)
    }

    fun interpretWithGemini(transcript: String): Result? {
        val geminiResult = geminiBridge?.interpret(transcript)
        if (geminiResult != null) return geminiResult

        Log.i(TAG, "Gemini REST unfulfilled; falling back to ultra-fast Groq intent bridge")
        return groqBridge?.interpret(transcript)
    }

    data class Result(
        val action: JSONObject?,   // non-null → execute a device action
        val reply: String          // always set – what to speak back
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Parallel Local Fast-Path (<50ms deterministic execution)
    // ─────────────────────────────────────────────────────────────────────────
    fun checkFastPath(chunkTranscript: String, partial: Boolean = false): Result? {
        val t = normalizeCommand(chunkTranscript)
        if (t.isBlank()) return null

        // 1. Check VoiceLearningStore / custom shortcuts
        val learnedAction = if (partial) {
            VoiceLearningStore.findExactActionForTranscript(t)
        } else {
            VoiceLearningStore.findActionForTranscript(t)
        }
        if (learnedAction != null) {
            Log.i(TAG, "Parallel fast-path matched a custom shortcut")
            return validatedFastPath(t, actionResultFromName(learnedAction), partial, exactShortcut = true)
        }

        // 2. Check deterministic rules before waiting for a model tool call.
        val single = processSingle(t, chunkTranscript)
        if (partial && !isCompletePartialFastPathCommand(t, single.action?.optString("action").orEmpty())) {
            return null
        }
        return validatedFastPath(t, single, partial)
    }

    private fun validatedFastPath(
        transcript: String,
        result: Result,
        partial: Boolean,
        exactShortcut: Boolean = false
    ): Result? {
        val action = result.action ?: return null
        val actionName = action.optString("action")
        if (actionName !in setOf(
                "flashlightOn", "flashlightOff",
                "volumeUp", "volumeDown", "mute",
                "toggleWifi", "toggleBluetooth", "adjustBrightness",
                "setAlarm", "setTimer", "makeCall", "openApp",
                "openCamera", "takePhoto", "takeSelfie",
                "screenshot", "globalBack", "globalHome", "lockScreen"
            ) ||
            !ActionPayloadValidator.isValidActionPayload(actionName, action) ||
            (!exactShortcut && !hasCompleteFastPathDetails(transcript, actionName, action))
        ) return null
        if (partial && !exactShortcut && !isCompletePartialFastPathCommand(transcript, actionName)) return null
        return result
    }

    private fun hasCompleteFastPathDetails(transcript: String, actionName: String, action: JSONObject): Boolean {
        return when (actionName) {
            "setTimer" -> Regex("\\b\\d+\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b")
                .containsMatchIn(transcript)
            "setAlarm" -> Regex("\\b(?:at|for)\\s*\\d{1,2}\\b|\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b")
                .containsMatchIn(transcript)
            "makeCall" -> action.optString("number").isNotBlank()
            "openApp" -> action.optString("appName").isNotBlank() ||
                action.optString("packageName").isNotBlank()
            else -> true
        }
    }

    private fun isCompletePartialFastPathCommand(transcript: String, actionName: String): Boolean {
        val phrases = when (actionName) {
            "flashlightOn" -> setOf("turn on flashlight", "flashlight on", "turn on torch", "torch on", "turn on flash", "flash on")
            "flashlightOff" -> setOf("turn off flashlight", "flashlight off", "turn off torch", "torch off", "turn off flash", "flash off")
            "volumeUp" -> setOf("volume up", "increase volume", "raise volume", "louder")
            "volumeDown" -> setOf("volume down", "decrease volume", "lower volume", "quieter")
            "mute" -> setOf("mute", "mute volume", "silence phone")
            "toggleWifi" -> setOf("wifi on", "turn on wifi", "wifi off", "turn off wifi")
            "toggleBluetooth" -> setOf("bluetooth on", "turn on bluetooth", "bluetooth off", "turn off bluetooth")
            "adjustBrightness" -> setOf("brightness up", "increase brightness", "brightness down", "decrease brightness")
            "screenshot" -> setOf("screenshot", "take screenshot", "take a screenshot")
            "openCamera" -> setOf("open camera", "launch camera", "camera on")
            "globalBack" -> setOf("go back", "press back", "navigate back")
            "globalHome" -> setOf("go home", "press home", "home screen")
            "lockScreen" -> setOf("lock screen", "lock phone", "lock device")
            else -> emptySet()
        }
        return transcript in phrases
    }

    // ─────────────────────────────────────────────────────────────────────────
    fun process(transcript: String): Result {
        val t = normalizeCommand(transcript)
        Log.d(TAG, "Processing transcript (${transcript.length} characters)")

        // 1. Check VoiceLearningStore
        val learnedAction = VoiceLearningStore.findActionForTranscript(t)
        if (learnedAction != null) {
            return actionResultFromName(learnedAction)
        }

        // 2. Check Compound Commands ("and then", "after that", "then", "and")
        val compoundResult = checkCompoundCommands(transcript, t)
        if (compoundResult != null) {
            return compoundResult
        }

        // 3. Process Single Command
        val singleResult = processSingle(t, transcript)

        if (singleResult.action != null) {
            val actionName = singleResult.action.optString("action", "")
            if (actionName.isNotBlank() && actionName != "MULTI_ACTION") {
                VoiceLearningStore.learn(transcript, actionName)
            }
        }

        return singleResult
    }

    private fun checkCompoundCommands(rawTranscript: String, normalized: String): Result? {
        val delimiters = listOf(" and then ", " after that ", " and also ", " then ")
        for (delim in delimiters) {
            if (normalized.contains(delim)) {
                val parts = normalized.split(delim).map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    val subActions = mutableListOf<JSONObject>()
                    val subReplies = mutableListOf<String>()

                    for (part in parts) {
                        val subResult = processSingle(part, part)
                        if (subResult.action != null) {
                            subActions.add(subResult.action)
                            subReplies.add(subResult.reply)
                        }
                    }

                    if (subActions.size >= 2) {
                        val multi = JSONObject().apply {
                            put("action", "MULTI_ACTION")
                            val arr = JSONArray()
                            subActions.forEach { arr.put(it) }
                            put("actions", arr)
                        }
                        val combinedReply = subReplies.joinToString(", and ")
                        Log.i(TAG, "Compound command resolved into ${subActions.size} actions")
                        return Result(multi, combinedReply)
                    }
                }
            }
        }
        return null
    }

    private fun processSingle(t: String, rawTranscript: String): Result {
        if (t.startsWith("don t ") || t.startsWith("do not ") || t.startsWith("never ")) {
            return Result(null, "Okay, I won't do that.")
        }

        return when {

            // ── Basic lock-screen-safe commands ──────────────────────────────
            t.containsAny("set timer for", "start timer", "timer for", "set timer") -> {
                val seconds = extractDurationSeconds(t)
                Result(
                    JSONObject().apply {
                        put("action", "setTimer")
                        put("seconds", seconds)
                    },
                    "Timer set for ${formatDuration(seconds)}"
                )
            }

            t.containsAny("set alarm", "set an alarm", "wake me up", "alarm for", "create alarm") -> {
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

            t.containsAny("open camera", "launch camera", "camera on", "start camera") ->
                Result(action("openCamera"), "Opening camera")

            t.containsAny("take a photo", "take photo", "click a photo", "capture photo",
                "take a picture", "take picture", "click picture", "snap photo") -> {
                val delay = extractDelay(t)
                Result(
                    JSONObject().apply {
                        put("action", "takePhoto")
                        put("delaySeconds", delay)
                    },
                    if (delay > 0) "Taking a photo in $delay seconds" else "Taking a photo"
                )
            }

            t.containsAny("take a selfie", "take selfie", "click selfie", "front camera selfie", "capture selfie") -> {
                val delay = extractDelay(t)
                Result(
                    JSONObject().apply {
                        put("action", "takeSelfie")
                        put("delaySeconds", delay)
                    },
                    if (delay > 0) "Taking a selfie in $delay seconds" else "Taking a selfie"
                )
            }

            t.containsAny("read my notification", "read notification", "read notifications", "read my message", "read message", "read messages", "any new message", "any new notification", "what did ", "latest notification") -> {
                val contact = if (t.contains("what did ")) {
                    t.substringAfter("what did ").substringBefore(" say").trim()
                } else null
                val summary = HeyBroNotificationListenerService.getLatestMessagesSummary(contact)
                Result(null, summary)
            }

            t.startsWith("reply to ") || t.startsWith("reply saying ") || t.startsWith("reply that ") || t.contains("send reply") -> {
                val (contactOrApp, replyMsg) = extractReplyContactAndMessage(t)
                val targetPkg = when {
                    contactOrApp.contains("whatsapp") -> "com.whatsapp"
                    contactOrApp.contains("telegram") -> "org.telegram.messenger"
                    contactOrApp.contains("signal") -> "org.thoughtcrime.securesms"
                    else -> ""
                }
                Result(
                    JSONObject().apply {
                        put("action", "replyNotification")
                        put("packageName", targetPkg)
                        put("contact", contactOrApp)
                        put("message", replyMsg)
                    },
                    if (replyMsg.isNotBlank()) "Replying: $replyMsg" else "Replying to message"
                )
            }

            t.containsAny("call ", "dial ", "phone ") && !t.containsAny("call me", "phone me", "call log") -> {
                val (contact, simSlot) = extractCallTargetAndSim(t)
                Result(
                    JSONObject().apply {
                        put("action", "makeCall")
                        put("number", contact)
                        if (simSlot != null) put("simSlot", simSlot)
                    },
                    "Calling $contact${if (simSlot != null) " from SIM $simSlot" else ""}"
                )
            }

            t.containsAny(
                "turn on flashlight", "flashlight on", "turn on torch", "torch on",
                "turn on flash", "turn flash on", "turn on the flashlight", "flash on", "light on",
                "turn on light", "open flashlight", "open torch",
                "enable flashlight", "enable torch", "switch on flashlight", "switch on torch"
            ) || t in setOf("torch", "flashlight", "flash") ->
                Result(action("flashlightOn"), "Turning on flashlight")

            t.containsAny(
                "turn off flashlight", "flashlight off", "turn off torch", "torch off",
                "turn off flash", "turn flash off", "turn off the flashlight", "flash off", "light off",
                "turn off light", "disable flashlight", "disable torch", "stop torch", "close flashlight",
                "switch off flashlight", "switch off torch", "shut off flashlight", "shut off torch"
            ) ->
                Result(action("flashlightOff"), "Turning off flashlight")

            t.containsAny("hotspot on", "turn on hotspot", "enable hotspot", "start hotspot", "switch on hotspot") ->
                Result(action("toggleHotspot", "enabled" to true), "Turning on hotspot")

            t.containsAny("hotspot off", "turn off hotspot", "disable hotspot", "stop hotspot", "switch off hotspot") ->
                Result(action("toggleHotspot", "enabled" to false), "Turning off hotspot")

            t.containsAny("wifi on", "turn on wifi", "enable wifi", "start wifi", "switch on wifi") ->
                Result(action("toggleWifi", "enabled" to true), "Turning on Wi-Fi")

            t.containsAny("wifi off", "turn off wifi", "disable wifi", "stop wifi", "switch off wifi") ->
                Result(action("toggleWifi", "enabled" to false), "Turning off Wi-Fi")

            t.containsAny("bluetooth on", "turn on bluetooth", "enable bluetooth", "start bluetooth", "switch on bluetooth") ->
                Result(action("toggleBluetooth", "enabled" to true), "Turning on Bluetooth")

            t.containsAny("bluetooth off", "turn off bluetooth", "disable bluetooth", "stop bluetooth", "switch off bluetooth") ->
                Result(action("toggleBluetooth", "enabled" to false), "Turning off Bluetooth")

            t.containsAny("airplane mode on", "turn on airplane", "enable airplane") ->
                Result(action("toggleAirplaneMode", "enabled" to true), "Turning on Airplane mode")

            t.containsAny("airplane mode off", "turn off airplane", "disable airplane") ->
                Result(action("toggleAirplaneMode", "enabled" to false), "Turning off Airplane mode")

            t.containsAny("battery saver on", "turn on battery saver", "enable battery saver") ->
                Result(action("batterySaver"), "Opening Battery Saver settings")

            t.containsAny("do not disturb", "turn on dnd", "enable dnd") ->
                Result(action("doNotDisturb"), "Opening Do Not Disturb settings")

            t.containsAny("auto rotate", "screen rotation") ->
                Result(action("autoRotate"), "Opening Display settings for rotation")

            t.containsAny("take a screenshot", "take screenshot", "capture screen", "screenshot") ->
                Result(action("screenshot"), "Taking screenshot")

            t.containsAny("go back", "press back", "navigate back", "back") && !t.contains("back to") ->
                Result(action("globalBack"), "Going back")

            t.containsAny("go home", "press home", "home screen") ->
                Result(action("globalHome"), "Going home")

            t.containsAny("open notification", "show notification", "notifications") ->
                Result(action("notifications"), "Opening notifications")

            t.containsAny("quick settings", "open quick settings") ->
                Result(action("quickSettings"), "Opening quick settings")

            t.containsAny("recent apps", "show recents", "open recents", "overview") ->
                Result(action("recents"), "Showing recent apps")

            t.containsAny("lock screen", "lock phone", "lock device") ->
                Result(action("lockScreen"), "Locking screen")

            t.containsAny("volume up", "increase volume", "raise volume", "louder", "sound up", "turn it up", "more volume", "higher volume") ->
                Result(action("volumeUp"), "Increasing volume")

            t.containsAny("volume down", "decrease volume", "lower volume", "softer", "reduce volume", "sound down", "turn it down", "less volume") ->
                Result(action("volumeDown"), "Decreasing volume")

            t.containsAny("mute", "silence phone", "mute volume", "quiet", "silent") ->
                Result(action("mute"), "Muting volume")

            t.containsAny("pause music", "pause video", "stop music", "resume music", "play pause", "pause media") ->
                Result(action("mediaPlayPause"), "Toggling playback")

            t.containsAny("next song", "next track", "skip song", "next video") ->
                Result(action("mediaNext"), "Skipping to next")

            t.containsAny("previous song", "previous track", "last song") ->
                Result(action("mediaPrevious"), "Going to previous")

            // ── Scroll / Gestures ─────────────────────────────────────────────
            t.containsAny("scroll down", "page down", "swipe down") ->
                Result(action("scrollDown"), "Scrolling down")

            t.containsAny("scroll up", "page up", "swipe up") ->
                Result(action("scrollUp"), "Scrolling up")

            t.containsAny("swipe left") ->
                Result(action("swipeLeft"), "Swiping left")

            t.containsAny("swipe right") ->
                Result(action("swipeRight"), "Swiping right")

            // ── WhatsApp & Messages ──────────────────────────────────────────
            t.containsAny("send whatsapp to", "whatsapp to", "send message to", "message to") -> {
                val (contact, msg) = extractContactAndMessage(t)
                Result(
                    JSONObject().apply {
                        put("action", "sendWhatsApp")
                        put("contact", contact)
                        put("message", msg)
                    },
                    "Sending WhatsApp message to $contact"
                )
            }

            t.containsAny("send sms to", "send text to") -> {
                val (contact, msg) = extractContactAndMessage(t)
                Result(
                    JSONObject().apply {
                        put("action", "sendSms")
                        put("number", contact)
                        put("message", msg)
                    },
                    "Sending SMS to $contact"
                )
            }

            // ── App Launching ─────────────────────────────────────────────────
            t.containsAny("open ", "launch ", "start ") && !t.containsAny("open camera", "open maps", "open settings", "open notification", "open quick settings", "open recents") -> {
                val app = extractAppName(t)
                Result(
                    JSONObject().apply {
                        put("action", "openApp")
                        put("appName", app)
                    },
                    "Opening $app"
                )
            }

            // ── Media Playback ────────────────────────────────────────────────
            t.containsAny("play ") && !t.containsAny("play pause") -> {
                val app = if (t.contains("spotify")) "Spotify" else "YouTube"
                val q = extractMediaQuery(t, app)
                Result(
                    JSONObject().apply {
                        put("action", "playMedia")
                        put("query", q)
                        put("app", app)
                    },
                    "Playing $q on $app"
                )
            }

            // ── Web Search & Navigation ───────────────────────────────────────
            t.containsAny("search for ", "search ", "google ", "look up ") -> {
                val q = extractSearchQuery(t)
                Result(
                    JSONObject().apply {
                        put("action", "searchWeb")
                        put("query", q)
                    },
                    "Searching for $q"
                )
            }

            t.containsAny("navigate to ", "directions to ", "maps to ", "open maps") -> {
                val loc = extractLocationQuery(t)
                Result(
                    JSONObject().apply {
                        put("action", "openMaps")
                        put("query", loc)
                    },
                    if (loc.isNotBlank()) "Navigating to $loc" else "Opening Maps"
                )
            }

            // ── Settings ─────────────────────────────────────────────────────
            t.containsAny("open settings", "phone settings", "device settings") ->
                Result(action("openSettings", "setting" to ""), "Opening Settings")

            t.containsAny("brightness up", "increase brightness") ->
                Result(action("adjustBrightness", "direction" to "up"), "Increasing brightness")

            t.containsAny("brightness down", "decrease brightness") ->
                Result(action("adjustBrightness", "direction" to "down"), "Decreasing brightness")

            // ── General / Social / Info ──────────────────────────────────────
            t.containsAny("what is the time", "what time is it", "tell me time", "current time", "what's the time", "time please", "the time", "check time") -> {
                val cal = Calendar.getInstance()
                Result(null, "It is ${formatTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}.")
            }

            t.containsAny("what is the date", "today's date", "what day is it", "current date", "what's the date", "today date") -> {
                val cal = Calendar.getInstance()
                val dateStr = "${cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.getDefault())}, ${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault())} ${cal.get(Calendar.DAY_OF_MONTH)}"
                Result(null, "Today is $dateStr.")
            }

            t.containsAny("battery level", "battery percentage", "how much battery", "check battery", "battery status", "phone battery", "my battery") -> {
                val level = getBatteryPercentage()
                Result(null, if (level >= 0) "Your battery is at $level percent." else "Battery information unavailable.")
            }

            t.containsAny("hello", "hi bro", "hey there", "what's up") ->
                Result(null, "Hey! I'm ready. What do you need?")

            t.containsAny("who are you", "what are you", "your name") ->
                Result(null, "I'm Hey Bro, your AI voice assistant. I run locally and connect to the cloud when needed.")

            t.containsAny("thank you", "thanks") ->
                Result(null, "Anytime! I'm here when you need me.")

            t.containsAny("how are you", "how you doing") ->
                Result(null, "I'm doing great, ready to help you out.")

            t.containsAny("tell me a joke", "say a joke", "joke") ->
                Result(null, jokes.random())

            t.containsAny("stop listening", "go to sleep", "goodbye", "bye bro", "sleep", "shut up", "cancel", "nevermind") ->
                Result(action("sleep"), "Going to sleep. Say Hey Bro to wake me up.")

            else -> {
                Log.d(TAG, "No local rule matched")
                Result(null, "Sorry, I didn't understand. Try asking for flashlight, alarms, calls, notifications, or general questions.")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun normalizeCommand(transcript: String): String {
        var normalized = transcript
            .lowercase()
            .replace(Regex("[^a-z0-9:%$€£ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Strip wake phrase prefixes
        normalized = normalized
            .replace(Regex("^(hey bro|hey broo|hey broh|hay bro|he bro|hey bruh|hey brother|hello bro|ok bro|okay bro|hi bro|yo bro|oye bro|ay bro|ey bro|bro)\\s+"), "")
            .replace(Regex("^(can you please|could you please|please|kindly|can you|could you|would you|will you|i want to|i want you to|go ahead and|just|hey|listen)\\s+"), "")
            .replace(Regex("\\b(the|a|an)\\b\\s+"), "")

        // Phonetic homophones and typos
        normalized = normalized
            .replace(Regex("\\bflash\\s+light\\b"), "flashlight")
            .replace(Regex("\\bwhat(?:'s|s)?\\s+app\\b"), "whatsapp")
            .replace(Regex("\\b(?:you|u)\\s+tube\\b"), "youtube")
            .replace(Regex("\\bwi(?:-|\\s+)fi\\b"), "wifi")
            .replace(Regex("\\bblue(?:-|\\s+)tooth\\b"), "bluetooth")
            .replace(Regex("\\bhot(?:-|\\s+)spot\\b"), "hotspot")
            .replace(Regex("\\bcall\\s+to\\b"), "call")
            .replace(Regex("\\btake\\s+a\\s+selfie\\b"), "take selfie")
            .replace(Regex("\\btake\\s+a\\s+photo\\b"), "take photo")
            .replace(Regex("\\btake\\s+a\\s+screenshot\\b"), "screenshot")
            .replace(Regex("\\bturn\\s+on\\s+the\\b"), "turn on")
            .replace(Regex("\\bturn\\s+off\\s+the\\b"), "turn off")
            .replace(Regex("\\b(switch|put|set)\\s+(on)\\s+"), "turn on ")
            .replace(Regex("\\b(switch|put|set)\\s+(off)\\s+"), "turn off ")
            .replace(Regex("\\b(turn|switch|put)\\s+([a-z ]+?)\\s+(on|off)\\b"), "turn $3 $2")
            .replace(Regex("\\b(enable|activate)\\s+"), "turn on ")
            .replace(Regex("\\b(disable|deactivate)\\s+"), "turn off ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized
    }

    private fun extractCallTargetAndSim(t: String): Pair<String, Int?> {
        val triggers = listOf("call ", "dial ", "phone ")
        var rawTarget = t
        for (trigger in triggers) {
            val idx = t.indexOf(trigger)
            if (idx >= 0) {
                rawTarget = t.substring(idx + trigger.length).trim()
                break
            }
        }

        var simSlot: Int? = null
        if (rawTarget.contains("sim 1") || rawTarget.contains("sim one")) {
            simSlot = 1
        } else if (rawTarget.contains("sim 2") || rawTarget.contains("sim two")) {
            simSlot = 2
        }

        val cleaned = rawTarget
            .replace(Regex("""\b(from|using|on|with)\s+sim\s*(1|2|one|two)\b"""), "")
            .replace(Regex("""\bsim\s*(1|2|one|two)\b"""), "")
            .trim()

        return Pair(if (cleaned.isNotBlank()) cleaned else rawTarget, simSlot)
    }

    private fun extractReplyContactAndMessage(t: String): Pair<String, String> {
        val sayingIdx = when {
            t.contains(" saying ") -> t.indexOf(" saying ")
            t.contains(" that ") -> t.indexOf(" that ")
            else -> -1
        }

        val message = if (sayingIdx >= 0) {
            val keyword = if (t.contains(" saying ")) " saying " else " that "
            t.substring(sayingIdx + keyword.length).trim()
        } else {
            t.removePrefix("reply to ")
             .removePrefix("reply saying ")
             .removePrefix("reply that ")
             .removePrefix("reply ")
             .trim()
        }

        val contactOrApp = if (sayingIdx >= 0 && t.startsWith("reply to ")) {
            t.substring("reply to ".length, sayingIdx).trim()
        } else if (t.startsWith("reply to ")) {
            t.removePrefix("reply to ").trim()
        } else {
            ""
        }

        return Pair(contactOrApp, message)
    }

    private fun extractTime(t: String): Pair<Int, Int> {
        val r12 = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)")
        val m12 = r12.find(t)
        if (m12 != null) {
            var h = m12.groupValues[1].toInt()
            val m = m12.groupValues[2].toIntOrNull() ?: 0
            val ampm = m12.groupValues[3]
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            return Pair(h, m)
        }

        val r24 = Regex("(\\d{1,2}):(\\d{2})")
        val m24 = r24.find(t)
        if (m24 != null) {
            return Pair(m24.groupValues[1].toInt(), m24.groupValues[2].toInt())
        }

        val rSimple = Regex("(?:at|for)\\s*(\\d{1,2})\\s*(?:o'?clock)?")
        val mSimple = rSimple.find(t)
        if (mSimple != null) {
            val h = mSimple.groupValues[1].toInt()
            return Pair(if (h <= 12) (h + 12).coerceAtMost(23) else h, 0)
        }

        return Pair(7, 0)
    }

    private fun extractAlarmLabel(t: String): String {
        val markers = listOf("named ", "called ", "label ")
        for (marker in markers) {
            val idx = t.indexOf(marker)
            if (idx >= 0) {
                return t.substring(idx + marker.length).trim()
            }
        }
        return "Wake up"
    }

    private fun extractDurationSeconds(t: String): Int {
        val r = Regex("(?:for|in)?\\s*(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)")
        val matches = r.findAll(t)
        var totalSec = 0
        for (m in matches) {
            val count = m.groupValues[1].toIntOrNull() ?: 0
            val unit = m.groupValues[2].lowercase()
            totalSec += when {
                unit.startsWith("hour") || unit.startsWith("hr") -> count * 3600
                unit.startsWith("minute") || unit.startsWith("min") -> count * 60
                else -> count
            }
        }
        return if (totalSec > 0) totalSec else 30
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

    private fun formatTime(h: Int, m: Int): String {
        val ampm = if (h < 12) "AM" else "PM"
        val h12  = when {
            h == 0   -> 12
            h > 12   -> h - 12
            else     -> h
        }
        return if (m == 0) "$h12 $ampm" else "$h12:%02d $ampm".format(m)
    }

    private fun extractDelay(t: String): Int {
        val r = Regex("(?:in|after)\\s*(\\d+)\\s*sec")
        val match = r.find(t)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractAppName(t: String): String {
        val markers = listOf("open ", "launch ", "start ")
        for (m in markers) {
            val idx = t.indexOf(m)
            if (idx >= 0) {
                return t.substring(idx + m.length).trim()
            }
        }
        return t
    }

    private fun extractContactAndMessage(t: String): Pair<String, String> {
        val markers = listOf(" saying ", " that ")
        for (m in markers) {
            val idx = t.indexOf(m)
            if (idx >= 0) {
                val prefix = t.substring(0, idx)
                val msg = t.substring(idx + m.length).trim()
                val contact = prefix
                    .replace(Regex("^(send whatsapp to|whatsapp to|send message to|message to|send sms to|send text to)\\s+"), "")
                    .trim()
                return Pair(contact, msg)
            }
        }
        val clean = t
            .replace(Regex("^(send whatsapp to|whatsapp to|send message to|message to|send sms to|send text to)\\s+"), "")
            .trim()
        return Pair(clean, "")
    }

    private fun extractSearchQuery(t: String): String {
        val markers = listOf("search for ", "search ", "google ", "look up ")
        for (m in markers) {
            val idx = t.indexOf(m)
            if (idx >= 0) {
                return t.substring(idx + m.length).trim()
            }
        }
        return t
    }

    private fun extractLocationQuery(t: String): String {
        val markers = listOf("navigate to ", "directions to ", "maps to ", "open maps")
        for (marker in markers) {
            val idx = t.indexOf(marker)
            if (idx >= 0) {
                return t.substring(idx + marker.length).trim()
            }
        }
        return t
    }

    private fun extractMediaQuery(t: String, app: String): String {
        val markers = listOf("on $app", "$app", "play ")
        var q = t
        for (m in markers) q = q.replace(m, "")
        return q.trim()
    }

    private fun getBatteryPercentage(): Int {
        val bm = appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun actionResultFromName(actionName: String): Result {
        if (actionName.startsWith("openApp:")) {
            val pkg = actionName.removePrefix("openApp:").trim()
            val friendly = when {
                pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                pkg.contains("youtube", ignoreCase = true) -> "YouTube"
                pkg.contains("spotify", ignoreCase = true) -> "Spotify"
                pkg.contains("chrome", ignoreCase = true) -> "Chrome"
                pkg.contains("map", ignoreCase = true) -> "Maps"
                else -> pkg.substringAfterLast('.')
            }
            return Result(action("openApp", "packageName" to pkg, "appName" to friendly), "Opening $friendly")
        }

        return when (actionName) {
            "flashlightOn"       -> Result(action("flashlightOn"), "Turning on flashlight")
            "flashlightOff"      -> Result(action("flashlightOff"), "Turning off flashlight")
            "mute"               -> Result(action("mute"), "Muting audio")
            "volumeUp"           -> Result(action("volumeUp"), "Increasing volume")
            "volumeDown"         -> Result(action("volumeDown"), "Decreasing volume")
            "takeSelfie"         -> Result(action("takeSelfie"), "Opening front camera")
            "takePhoto"          -> Result(action("takePhoto"), "Opening camera")
            "openCamera"         -> Result(action("openCamera"), "Opening camera")
            "screenshot"         -> Result(action("screenshot"), "Taking screenshot")
            "lockScreen"         -> Result(action("lockScreen"), "Locking screen")
            "batterySaver"       -> Result(action("batterySaver"), "Toggling battery saver")
            "doNotDisturb"       -> Result(action("doNotDisturb"), "Toggling Do Not Disturb")
            "notifications"      -> Result(action("notifications"), "Showing notifications")
            "quickSettings"      -> Result(action("quickSettings"), "Opening quick settings")
            "recents"            -> Result(action("recents"), "Opening recent apps")
            "globalBack"         -> Result(action("globalBack"), "Going back")
            "globalHome"         -> Result(action("globalHome"), "Going home")
            "mediaPlayPause"     -> Result(action("mediaPlayPause"), "Toggling playback")
            "mediaNext"          -> Result(action("mediaNext"), "Skipping track")
            "mediaPrevious"      -> Result(action("mediaPrevious"), "Previous track")
            "toggleWifi"         -> Result(action("toggleWifi", "enabled" to true), "Turning on Wi-Fi")
            "toggleBluetooth"    -> Result(action("toggleBluetooth", "enabled" to true), "Turning on Bluetooth")
            "toggleAirplaneMode" -> Result(action("toggleAirplaneMode", "enabled" to true), "Toggling Airplane mode")
            "toggleHotspot"      -> Result(action("toggleHotspot", "enabled" to true), "Toggling hotspot")
            "openSettings"       -> Result(action("openSettings"), "Opening Settings")
            "openMaps"           -> Result(action("openMaps"), "Opening Maps")
            "setAlarm"           -> Result(action("setAlarm", "hour" to 7, "minute" to 0, "message" to "Wake up"), "Alarm set for 7:00")
            "setTimer"           -> Result(action("setTimer", "seconds" to 300, "message" to "Timer"), "Timer set for 5 minutes")
            "makeCall"           -> Result(action("makeCall", "number" to ""), "Opening phone dialer")
            "openApp"            -> Result(action("openApp", "appName" to "App"), "Opening app")
            "playMedia"          -> Result(action("playMedia", "query" to "", "app" to "YouTube"), "Playing media")
            else -> Result(null, "I’m ready to help.")
        }
    }

    private fun action(act: String, vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply {
            put("action", act)
            pairs.forEach { (k, v) -> put(k, v) }
        }

    private fun String.containsAny(vararg tokens: String) = tokens.any { this.contains(it) }

    private val jokes = listOf(
        "Why don't scientists trust atoms? Because they make up everything!",
        "Why did the computer go to the doctor? Because it had a virus!",
        "What do you call a fake noodle? An impasta!",
        "Why did the scarecrow win an award? Because he was outstanding in his field!"
    )
}
