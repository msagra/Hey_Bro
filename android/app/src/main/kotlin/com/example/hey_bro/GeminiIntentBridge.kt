package com.example.hey_bro

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiIntentBridge(private val context: Context) {
    private val supportedActions = setOf(
        "openApp", "openCamera", "takePhoto", "takeSelfie", "setAlarm", "setTimer",
        "makeCall", "replyNotification", "sendWhatsApp", "searchWeb", "openMaps", "playMedia", "screenshot",
        "scrollDown", "scrollUp", "volumeUp", "volumeDown", "mute", "flashlightOn",
        "flashlightOff", "toggleWifi", "toggleBluetooth", "toggleAirplaneMode",
        "toggleHotspot", "adjustBrightness", "openSettings", "lockScreen", "notifications",
        "quickSettings", "recents", "mediaPlayPause", "mediaNext", "mediaPrevious",
        "batterySaver", "doNotDisturb", "autoRotate", "createCalendarEvent", "sendSms",
        "doubleTap", "longPress", "swipeLeft", "swipeRight", "globalBack", "globalHome",
        "sleep"
    )
    private val prefs by lazy {
        context.getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)
    }

    fun interpret(transcript: String): LocalCommandProcessor.Result? {
        val key = prefs.getString("gemini_api_key", "")?.trim().let { 
            if (it.isNullOrBlank()) MainActivity.DEFAULT_GEMINI_KEY else it 
        }
        if (key.isBlank()) return null

        return try {
            val requestBody = JSONObject().apply {
                val systemInstruction = "You are 'Hey Bro', an intelligent voice assistant like Alexa / Amazon Echo. " +
                    "Respond with a single JSON object with keys: \"action\" (string), \"params\" (object), and \"reply\" (string). " +
                    "For device operations, action must be one of: openApp, openCamera, takePhoto, takeSelfie, setAlarm, setTimer, makeCall, replyNotification, sendWhatsApp, searchWeb, openMaps, playMedia, screenshot, scrollDown, scrollUp, volumeUp, volumeDown, mute, flashlightOn, flashlightOff, toggleWifi, toggleBluetooth, toggleAirplaneMode, toggleHotspot, adjustBrightness, openSettings, lockScreen, notifications, quickSettings, recents, mediaPlayPause, mediaNext, mediaPrevious, batterySaver, doNotDisturb, autoRotate, createCalendarEvent, sendSms, globalBack, globalHome, sleep. " +
                    "For makeCall, params can include \"number\" and optional \"simSlot\" (1 or 2). " +
                    "For replyNotification, params must include \"message\" and optional \"contact\" / \"packageName\". " +
                    "For all general questions, facts, calculations, stories, or chit-chat, set \"action\" to \"none\" with \"params\": {} and provide a concise, direct, natural spoken answer strictly under 20 words in \"reply\". NEVER use Markdown formatting, bullet points, or lists."
                
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", systemInstruction))
                        .put(JSONObject().put("text", transcript))
                    )
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 200)
                    put("responseMimeType", "application/json")
                })
            }

            val restModel = BuildConfig.GEMINI_REST_MODEL.ifBlank { "gemini-3.5-flash" }
            val modelsToTry = if (restModel == "gemini-3.5-flash") {
                listOf("gemini-3.5-flash", "gemini-3.8-flash", "gemini-flash-latest")
            } else {
                listOf(restModel, "gemini-3.5-flash", "gemini-flash-latest")
            }
            var responseCode = -1
            var raw = ""

            for (model in modelsToTry) {
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.connectTimeout = 4_000
                        connection.readTimeout = 7_000
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.setRequestProperty("Accept", "application/json")

                        connection.outputStream.use { stream ->
                            stream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                            stream.flush()
                        }

                        responseCode = connection.responseCode
                        if (responseCode in 200..299) {
                            raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } else {
                            Log.w("GeminiIntentBridge", "Model $model returned $responseCode, trying next fallback...")
                        }
                    } finally {
                        connection.disconnect()
                    }

                    if (raw.isNotBlank()) break
                } catch (e: Exception) {
                    Log.w("GeminiIntentBridge", "Error calling $model: ${e.message}")
                    break
                }
            }

            if (raw.isBlank() || responseCode !in 200..299) {
                Log.w(
                    "GeminiIntentBridge",
                    if (responseCode < 0) "Gemini REST request failed before receiving a response"
                    else "Gemini REST request failed with HTTP $responseCode"
                )
                return null
            }
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates") ?: return null
            val first = candidates.optJSONObject(0) ?: return null
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null

            var modelText = ""
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    modelText = text
                    break
                }
            }

            if (modelText.isBlank()) return null
            val jsonObject = extractJsonObject(modelText) ?: return null
            val action = jsonObject.optString("action", "").trim()
            var reply = jsonObject.optString("reply", jsonObject.optString("response", "Done.")).trim()
            // Clean up any stray markdown
            reply = reply.replace(Regex("[*#_`~]"), "").replace(Regex("\\s+"), " ").trim()

            if (action.isBlank() || action == "none" || action !in supportedActions) {
                return if (reply.isNotBlank()) {
                    LocalCommandProcessor.Result(null, reply)
                } else {
                    null
                }
            }

            val params = jsonObject.optJSONObject("params") ?: JSONObject()
            val actionJson = JSONObject().apply {
                put("action", action)
                params.keys().forEach { key ->
                    put(key, params.get(key))
                }
            }
            LocalCommandProcessor.Result(actionJson, if (reply.isNotBlank()) reply else "Done.")
        } catch (e: Exception) {
            Log.w("GeminiIntentBridge", "Gemini reasoning failed: ${e.message}")
            null
        }
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val cleaned = text.trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        val raw = cleaned.substring(start, end + 1)
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }
}
