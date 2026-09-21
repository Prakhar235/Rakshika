package com.rakshika.app.risk

import android.util.Log
import com.rakshika.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Raw REST to OpenAI's Chat Completions API, straight from the device (same no-SDK style as
 * [com.rakshika.app.routing.GoogleRouting]), with `OPENAI_API_KEY` from `local.properties` via
 * `BuildConfig`. Shared by the assess and learn calls in [RiskModelClient].
 */
internal object OpenAiChat {
    private const val TAG = "OpenAiChat"
    private const val URL_STR = "https://api.openai.com/v1/chat/completions"
    const val MODEL = "gpt-4o-mini"

    val isConfigured: Boolean get() = BuildConfig.OPENAI_API_KEY.isNotBlank()

    /** POSTs [body] (model is added here) and returns the first choice's `message` object, or null on any failure. */
    suspend fun complete(body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.w(TAG, "OPENAI_API_KEY not set in local.properties — skipping the model call")
            return@withContext null
        }
        val conn = URL(URL_STR).openConnection() as? HttpURLConnection ?: return@withContext null
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            body.put("model", MODEL)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "POST $URL_STR -> HTTP $code: $err")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        } catch (e: Exception) {
            Log.w(TAG, "POST $URL_STR failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
