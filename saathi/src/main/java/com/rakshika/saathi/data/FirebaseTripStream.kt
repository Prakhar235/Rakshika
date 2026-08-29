package com.rakshika.saathi.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** connected = is the stream currently attached; tree = merged node, or null when it doesn't exist. */
data class StreamUpdate(val connected: Boolean, val tree: JSONObject?)

/**
 * Reads the trip node as a live stream using Firebase Realtime Database's REST
 * streaming protocol: GET with `Accept: text/event-stream` stays open and pushes
 *
 *   event: put      data: {"path":"/","data":{...whole node...}}
 *   event: patch    data: {"path":"/location","data":{...changed keys...}}
 *   event: keep-alive
 *
 * We keep a local mirror of the node, apply each event, and emit the merged tree.
 * A dropped connection reconnects after [RECONNECT_MS] (Firebase resends a full `put`).
 */
class FirebaseTripStream(private val url: String = Config.streamUrl) {

    fun events(): Flow<StreamUpdate> = flow {
        while (currentCoroutineContext().isActive) {
            var mirror = JSONObject()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "text/event-stream")
                    instanceFollowRedirects = true
                    connectTimeout = 10_000
                    // Firebase sends keep-alives well within this; a real drop unblocks readLine().
                    readTimeout = 70_000
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "stream HTTP $code")
                    emit(StreamUpdate(false, null))
                    delay(RECONNECT_MS)
                    continue
                }

                conn.inputStream.bufferedReader().use { reader ->
                    var event: String? = null
                    val data = StringBuilder()
                    while (currentCoroutineContext().isActive) {
                        val line = try {
                            reader.readLine() ?: break
                        } catch (t: SocketTimeoutException) {
                            emit(StreamUpdate(false, null))   // stale — reconnect, keep last snapshot
                            break
                        }
                        when {
                            line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                            line.startsWith("data:") -> data.append(line.substringAfter("data:").trim())
                            line.isEmpty() -> {
                                val e = event
                                if (e == "put" || e == "patch") {
                                    mirror = applyEvent(mirror, e, data.toString())
                                    emit(StreamUpdate(true, mirror.takeIf { it.length() > 0 }))
                                }
                                event = null
                                data.setLength(0)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stream error: ${e.message}")
                emit(StreamUpdate(false, null))
            } finally {
                conn?.disconnect()
            }
            delay(RECONNECT_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun applyEvent(mirror: JSONObject, event: String, dataJson: String): JSONObject {
        val envelope = runCatching { JSONObject(dataJson) }.getOrNull() ?: return mirror
        val path = envelope.optString("path", "/")
        val payload = if (envelope.isNull("data")) null else envelope.opt("data")

        // Whole-node replace / delete.
        if (path == "/") {
            if (event == "put") return (payload as? JSONObject) ?: JSONObject()
            (payload as? JSONObject)?.let { d -> d.keys().forEach { mirror.put(it, d.get(it)) } }
            return mirror
        }

        // One child key, e.g. "/location" or "/sos" — the shapes Rakshika actually writes.
        val key = path.trim('/').substringBefore('/')
        if (payload == null) {
            mirror.remove(key)
        } else if (event == "put") {
            mirror.put(key, payload)
        } else { // patch merge
            val existing = mirror.optJSONObject(key) ?: JSONObject()
            (payload as? JSONObject)?.let { d -> d.keys().forEach { existing.put(it, d.get(it)) } }
            mirror.put(key, existing)
        }
        return mirror
    }

    private companion object {
        const val TAG = "SaathiStream"
        const val RECONNECT_MS = 3_000L
    }
}
