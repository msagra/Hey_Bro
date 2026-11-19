package com.example.hey_bro

import org.json.JSONObject

internal object ActionPayloadValidator {
    const val MAX_MULTI_ACTION_STEPS = 16

    private val knownActions = setOf(
        "click", "setText", "globalBack", "globalHome", "lockScreen",
        "notifications", "quickSettings", "recents", "globalAction",
        "controlHardware", "openApp", "openCamera", "takePhoto", "takeSelfie",
        "setAlarm", "setTimer", "makeCall", "replyNotification", "readNotifications",
        "sendWhatsApp", "searchWeb", "openMaps", "playMedia", "screenshot",
        "scrollDown", "scrollUp", "volumeUp", "volumeDown", "mute",
        "mediaPlayPause", "mediaNext", "mediaPrevious", "flashlightOn", "flashlightOff",
        "toggleWifi", "toggleBluetooth", "toggleAirplaneMode", "toggleHotspot",
        "adjustBrightness", "openSettings", "batterySaver", "doNotDisturb", "autoRotate",
        "createCalendarEvent", "sendSms", "doubleTap", "longPress", "swipeLeft",
        "swipeRight", "sleep"
    )

    fun isValidCommandEnvelope(
        json: JSONObject,
        extendedActions: Set<String> = emptySet()
    ): Boolean {
        return if (json.optString("type") == "MULTI_ACTION" ||
            json.optString("action") == "MULTI_ACTION"
        ) {
            isValidMultiAction(json, extendedActions)
        } else {
            isValidActionEnvelope(json, extendedActions)
        }
    }

    fun isValidActionEnvelope(
        json: JSONObject,
        extendedActions: Set<String> = emptySet()
    ): Boolean {
        if ((json.has("type") && json.opt("type") !is String) ||
            (json.has("action") && json.opt("action") !is String) ||
            (json.has("reply") && json.opt("reply") !is String) ||
            (json.has("voiceConfirmed") && json.opt("voiceConfirmed") !is Boolean)
        ) return false
        val type = json.optString("type")
        val action = json.optString("action")
        if (type.isNotBlank() && type != "ACTION") return false
        if (action.isBlank() || (json.has("params") && json.optJSONObject("params") == null)) return false
        return isValidActionPayload(action, mergeParams(json), extendedActions)
    }

    fun isValidMultiAction(
        json: JSONObject,
        extendedActions: Set<String> = emptySet()
    ): Boolean {
        val type = json.optString("type")
        val action = json.optString("action")
        if ((json.has("type") && json.opt("type") !is String) ||
            (json.has("action") && json.opt("action") !is String) ||
            (type.isNotBlank() && type != "MULTI_ACTION") ||
            (action.isNotBlank() && action != "MULTI_ACTION") ||
            (type != "MULTI_ACTION" && action != "MULTI_ACTION") ||
            (json.has("params") && json.optJSONObject("params") == null)
        ) return false
        if (json.has("voiceConfirmed") && json.opt("voiceConfirmed") !is Boolean) return false

        val actions = json.optJSONArray("actions") ?: return false
        if (actions.length() !in 1..MAX_MULTI_ACTION_STEPS) return false

        for (index in 0 until actions.length()) {
            val step = actions.optJSONObject(index) ?: return false
            if ((step.has("type") && step.optString("type") != "ACTION") ||
                (step.has("action") && step.opt("action") !is String) ||
                (step.has("settleMs") && step.opt("settleMs") !is Number)
            ) return false
            val stepAction = step.optString("action")
            if (stepAction.isBlank() ||
                (step.has("params") && step.optJSONObject("params") == null) ||
                !isValidActionPayload(stepAction, mergeParams(step), extendedActions) ||
                !isValidPrecondition(step.optJSONObject("precondition"), step.has("precondition")) ||
                !isBoundedNumber(step, "settleMs", 0L, 2_000L)
            ) return false
        }
        return true
    }

    fun requiresVoiceConfirmation(json: JSONObject): Boolean {
        if (json.optString("type") == "MULTI_ACTION" || json.optString("action") == "MULTI_ACTION") {
            val actions = json.optJSONArray("actions") ?: return false
            return (0 until actions.length()).any { index ->
                val action = actions.optJSONObject(index) ?: return@any false
                isSensitiveAction(action.optString("action"), mergeParams(action))
            }
        }
        return isSensitiveAction(json.optString("action"), mergeParams(json))
    }

    fun isValidActionPayload(
        action: String,
        json: JSONObject,
        extendedActions: Set<String> = emptySet()
    ): Boolean {
        if (action !in knownActions && action !in extendedActions) return false

        fun hasText(key: String) = json.opt(key) is String && json.optString(key).isNotBlank()
        fun hasNumber(key: String, min: Int, max: Int): Boolean {
            val value = json.opt(key) as? Number ?: return false
            val integer = value.toInt()
            return value.toDouble() == integer.toDouble() && integer in min..max
        }

        return when (action) {
            "makeCall" -> hasText("number") &&
                (!json.has("simSlot") || hasNumber("simSlot", 1, 2))
            "sendWhatsApp" -> hasText("contact") && hasText("message")
            "replyNotification" -> hasText("message") &&
                (hasText("packageName") || hasText("contact"))
            "sendSms" -> hasText("number") && hasText("message")
            "controlHardware" -> hasText("device") &&
                json.optString("device").lowercase() in
                setOf("flashlight", "volume", "wifi", "bluetooth", "airplane", "brightness") &&
                json.optString("state").lowercase() in
                setOf("on", "off", "toggle", "up", "down", "increase", "decrease", "mute")
            "toggleWifi", "toggleBluetooth", "toggleAirplaneMode" ->
                !json.has("enabled") || json.opt("enabled") is Boolean
            "setAlarm" -> hasNumber("hour", 0, 23) && hasNumber("minute", 0, 59)
            "setTimer" -> hasNumber("seconds", 1, 604_800)
            "openApp" -> hasText("appName") || hasText("packageName")
            "takePhoto", "takeSelfie" -> !json.has("delaySeconds") ||
                hasNumber("delaySeconds", 0, 60)
            "searchWeb", "openMaps", "playMedia" -> hasText("query")
            "click" -> hasText("targetText") || hasText("targetId")
            "setText" -> (hasText("targetText") || hasText("targetId")) && hasText("textValue")
            "adjustBrightness" -> json.optString("direction", "up").lowercase() in
                setOf("up", "down", "increase", "decrease")
            else -> true
        }
    }

    fun mergeParams(json: JSONObject): JSONObject {
        val merged = JSONObject(json.toString())
        val params = json.optJSONObject("params") ?: return merged
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!merged.has(key)) merged.put(key, params.get(key))
        }
        return merged
    }

    private fun isValidPrecondition(precondition: JSONObject?, present: Boolean): Boolean {
        if (!present) return true
        if (precondition == null) return false
        if (precondition.has("packageName") && precondition.opt("packageName") !is String) return false
        return isBoundedNumber(precondition, "timeoutMs", 0L, 5_000L)
    }

    private fun isBoundedNumber(json: JSONObject, key: String, min: Long, max: Long): Boolean {
        if (!json.has(key)) return true
        val value = json.opt(key) as? Number ?: return false
        return value.toDouble() == value.toLong().toDouble() && value.toLong() in min..max
    }

    private fun isSensitiveAction(action: String, params: JSONObject): Boolean {
        return action.lowercase() in setOf(
            "makecall", "sendwhatsapp", "replynotification", "sendsms",
            "toggleairplanemode", "lockscreen"
        ) || (action.equals("controlHardware", ignoreCase = true) &&
            params.optString("device").equals("airplane", ignoreCase = true))
    }
}
