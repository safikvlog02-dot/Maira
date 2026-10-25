package com.maira.assistant

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gemini এর সাথে যোগাযোগ: উত্তর (টেক্সট) ও মানুষের মতো কণ্ঠ (TTS) */
object GeminiClient {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    private fun post(key: String, model: String, body: String): String {
        val c = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val m = try {
                JSONObject(txt).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                txt.take(200)
            }
            throw RuntimeException("API ($code): $m")
        }
        return txt
    }

    /** history: (role, text) জোড়া, role = "user" বা "maira" */
    fun chat(key: String, model: String, system: String, history: List<Pair<String, String>>): String {
        val contents = JSONArray()
        for ((role, text) in history) {
            val r = if (role == "user") "user" else "model"
            if (contents.length() == 0 && r != "user") continue
            contents.put(
                JSONObject().put("role", r).put(
                    "parts", JSONArray().put(JSONObject().put("text", text))
                )
            )
        }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", contents)
            .toString()
        val res = post(key, model, body)
        return JSONObject(res)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text").trim()
    }

    /** কণ্ঠ তৈরি: ২৪ কিলোহার্জ, মনো, ১৬ বিট PCM বাইট ফেরত দেয় */
    fun tts(key: String, model: String, voice: String, style: String, text: String): ByteArray {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", "$style: $text"))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voice)
                            )
                        )
                    )
            )
            .toString()
        val res = post(key, model, body)
        val data = JSONObject(res)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getJSONObject("inlineData").getString("data")
        return Base64.decode(data, Base64.DEFAULT)
    }
}
