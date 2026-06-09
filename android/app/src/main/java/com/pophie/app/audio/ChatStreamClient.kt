package com.pophie.app.audio

import android.util.Log
import com.pophie.app.data.model.ChatRequest
import com.pophie.app.data.model.ChatResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 消费 POST /api/chat/stream 返回的 NDJSON 流（speak* → done）。
 * speak 事件携带可按句提前 TTS 的文本片段。
 */
class ChatStreamClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun stream(
        request: ChatRequest,
        onSpeak: suspend (String) -> Unit,
    ): ChatResponse = withContext(Dispatchers.IO) {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val bodyJson = moshi.adapter(ChatRequest::class.java).toJson(request)
        val url = baseUrl.trimEnd('/') + "/api/chat/stream"
        val httpRequest = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(httpRequest).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Chat stream HTTP ${resp.code}: ${resp.body?.string()}")
            }
            val reader = BufferedReader(InputStreamReader(resp.body?.byteStream()))
            val responseAdapter = moshi.adapter(ChatResponse::class.java)
            var chatResponse: ChatResponse? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = JSONObject(line)
                when (obj.optString("type")) {
                    "speak" -> {
                        val text = obj.optString("text")
                        if (text.isNotBlank()) onSpeak(text)
                    }
                    "error" -> throw RuntimeException(obj.optString("message", "Chat stream error"))
                    "done" -> {
                        val payload = obj.optJSONObject("response")
                            ?: throw RuntimeException("Chat stream: missing response")
                        chatResponse = responseAdapter.fromJson(payload.toString())
                    }
                }
            }
            chatResponse ?: throw RuntimeException("Chat stream: missing done")
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "PophieChat"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun logSpeak(text: String) {
            Log.d(TAG, "chat stream speak len=${text.length}")
        }
    }
}
