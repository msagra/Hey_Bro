package com.example.hey_bro

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct edge-to-cloud Groq API intent and reasoning bridge.
 * Provides ultra-fast (<250ms) inference using Llama 3.3 70B / Llama 3.1 8B
 * as a secondary fallback whenever Gemini Multimodal Live or Gemini REST
 * is experiencing latency or temporary connectivity issues.
 */
class GroqIntentBridge(private val context: Context) {

    companion object {
        private const val TAG = "GroqIntentBridge"
        private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val PRIMARY_MODEL = "qwen/qwen3.8-27b"
        private const val FAST_MODEL = "openai/gpt-oss-120b"
        private const val FALLBACK_MODEL = "openai/gpt-oss-20b"
    }

    private val supportedActions = setOf(
        "openApp", "openCamera", "takePhoto", "takeSelfie", "setAlarm", "setTimer",
        "makeCall", "replyNotification", "sendWhatsApp", "searchWeb", "openMaps", "playMedia", "screenshot",
        "scrollDown", "scrollUp", "volumeUp", "volumeDown", "mute", "flashlightOn",
        "flashlightOff", "toggleWifi", "toggleBluetooth", "toggleAirplaneMode",
        "toggleHotspot", "adjustBrightness", "openSettings", "lockScreen", "notifications",
        "quickSettings", "recents", "mediaPlayPause", "mediaNext", "mediaPrevious",
        "batterySaver", "doNotDisturb", "autoRotate", "createCalendarEvent", "sendSms",
        "globalBack", "globalHome", "sleep"
    )

    private val prefs by lazy {
        context.getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)
    }

    fun getApiKey(): String {
        val saved = prefs.getString("groq_api_key", "")?.trim() ?: ""
        return if (saved.isNotBlank()) saved else BuildConfig.GROQ_API_KEY.trim()
    }

    fun interpret(transcript: String): LocalCommandProcessor.Result? {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Log.d(TAG, "Groq API key is not configured, skipping Groq fallback.")
            return null
        }

        val systemPrompt = "You are 'Hey Bro', an intelligent Android voice assistant. " +
            "Return ONLY a single valid JSON object with keys: \"action\" (string), \"params\" (object), and \"reply\" (string). " +
            "Supported device action values: openApp, openCamera, takePhoto, takeSelfie, setAlarm, setTimer, " +
            "makeCall, replyNotification, sendWhatsApp, searchWeb, openMaps, playMedia, screenshot, scrollDown, " +
            "scrollUp, volumeUp, volumeDown, mute, flashlightOn, flashlightOff, toggleWifi, toggleBluetooth, " +
            "toggleAirplaneMode, toggleHotspot, adjustBrightness, openSettings, lockScreen, notifications, " +
            "quickSettings, recents, mediaPlayPause, mediaNext, mediaPrevious, batterySaver, doNotDisturb, " +
            "autoRotate, createCalendarEvent, sendSms, globalBack, globalHome, sleep. " +
            "For general Q&A, chit-chat, or knowledge, set action to \"none\", params to {}, and provide a concise, friendly spoken answer under 25 words in \"reply\". " +
            "Do NOT include markdown syntax (no asterisks, hash marks, or backticks)."

        val modelsToTry = listOf(PRIMARY_MODEL, FAST_MODEL, FALLBACK_MODEL, "llama-3.3-70b-versatile")

        for (model in modelsToTry) {
            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.1)
                    put("max_tokens", 250)
                    put("response_format", JSONObject().put("type", "json_object"))
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", transcript)
                        })
                    })
                }

                val conn = (URL(GROQ_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "HeyBroAssistant/1.0")
                }

                conn.outputStream.use { out ->
                    out.write(payload.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val root = JSONObject(body)
                    val choices = root.optJSONArray("choices") ?: continue
                    val firstChoice = choices.optJSONObject(0) ?: continue
                    val message = firstChoice.optJSONObject("message") ?: continue
                    val contentStr = message.optString("content", "")

                    if (contentStr.isNotBlank()) {
                        val parsed = extractJsonObject(contentStr) ?: continue
                        val action = parsed.optString("action", "").trim()
                        var reply = parsed.optString("reply", parsed.optString("response", "Done.")).trim()
                        reply = reply.replace(Regex("[*#_`~]"), "").replace(Regex("\\s+"), " ").trim()

                        val finalAction = if (action.isNotBlank() && action != "none" && action in supportedActions) {
                            JSONObject().apply {
                                put("action", action)
                                put("params", parsed.optJSONObject("params") ?: JSONObject())
                            }
                        } else null

                        Log.i(TAG, "Groq ($model) resolved intent: action=$action, reply='$reply'")
                        return LocalCommandProcessor.Result(finalAction, reply)
                    }
                } else {
                    Log.w(TAG, "Groq $model returned HTTP $code, trying next model...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Groq request failed with model $model: ${e.message}")
            }
        }

        return null
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return try {
                JSONObject(trimmed.substring(start, end + 1))
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}
