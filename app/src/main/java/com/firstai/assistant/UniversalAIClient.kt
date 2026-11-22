package com.firstai.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object UniversalAIClient {

    fun generateReply(apiKey: String, systemPrompt: String, userMessage: String): String {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            throw IllegalArgumentException("API key is empty. Open Settings and paste your AI key.")
        }

        return if (cleanKey.startsWith("AIza")) {
            callGemini(cleanKey, systemPrompt, userMessage)
        } else {
            callOpenAICompatible(
                apiKey = cleanKey,
                endpointUrl = "https://api.openai.com/v1/chat/completions",
                model = "gpt-4o-mini",
                systemPrompt = systemPrompt,
                userMessage = userMessage
            )
        }
    }

    private fun callGemini(apiKey: String, systemPrompt: String, userMessage: String): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
        }

        val combinedPrompt = "$systemPrompt\n\nUser:$userMessage"
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", combinedPrompt))
                    )
                )
            )
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val rawBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()

        val json = JSONObject(rawBody)
        if (code !in 200..299) {
            val errMsg = json.optJSONObject("error")?.optString("message") ?: "Gemini HTTP $code"
            throw RuntimeException(errMsg)
        }

        val text = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()

        return if (!text.isNullOrEmpty()) text else "No response."
    }

    private fun callOpenAICompatible(
        apiKey: String,
        endpointUrl: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): String {
        val url = URL(endpointUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userMessage))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val rawBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()

        val json = JSONObject(rawBody)
        if (code !in 200..299) {
            val errMsg = json.optJSONObject("error")?.optString("message") ?: "OpenAI HTTP $code"
            throw RuntimeException(errMsg)
        }

        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()

        return if (!content.isNullOrEmpty()) content else "No response."
    }
}
