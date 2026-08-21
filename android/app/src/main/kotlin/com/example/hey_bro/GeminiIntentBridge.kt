package com.example.hey_bro

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class GeminiIntentBridge(private val context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences("hey_bro_settings", Context.MODE_PRIVATE)
    }

    fun interpret(transcript: String): LocalCommandProcessor.Result? {
        val key = prefs.getString("gemini_api_key", "")?.trim() ?: ""
        if (key.isBlank()) return null

        return try {
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", transcript)
                    ))
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 256)
                })
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.use { stream ->
                stream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.w("GeminiIntentBridge", "Gemini API returned $responseCode")
                return null
            }

            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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
            val action = jsonObject.optString("action", "")
            val params = jsonObject.optJSONObject("params") ?: JSONObject()
            val reply = jsonObject.optString("reply", jsonObject.optString("response", "Done."))

            val actionJson = JSONObject().apply {
                put("action", action)
                params.keys().forEach { key ->
                    put(key, params.get(key))
                }
            }
            LocalCommandProcessor.Result(actionJson, reply)
        } catch (e: Exception) {
            Log.w("GeminiIntentBridge", "Gemini fallback failed", e)
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
