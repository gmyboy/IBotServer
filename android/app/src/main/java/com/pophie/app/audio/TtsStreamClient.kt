package com.pophie.app.audio

import android.util.Base64
import android.util.Log
import com.pophie.app.data.model.TtsRequest
import com.pophie.app.data.model.VoiceProsody
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
 * 消费 POST /api/tts/stream 返回的 NDJSON 流（meta → chunk* → done）。
 */
class TtsStreamClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    data class Meta(
        val format: String,
        val sampleRate: Int,
        val encoding: String,
    )

    data class StreamResult(
        val meta: Meta,
        val firstPacketMs: Int?,
    )

    suspend fun stream(
        text: String,
        voice: VoiceProsody?,
        voiceId: String?,
        onMeta: (Meta) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ): StreamResult = withContext(Dispatchers.IO) {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val bodyJson = moshi.adapter(TtsRequest::class.java).toJson(
            TtsRequest(text = text, voice = voice, voiceId = voiceId),
        )
        val url = baseUrl.trimEnd('/') + "/api/tts/stream"
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("TTS stream HTTP ${resp.code}: ${resp.body?.string()}")
            }
            val reader = BufferedReader(InputStreamReader(resp.body?.byteStream()))
            var meta: Meta? = null
            var firstPacketMs: Int? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = JSONObject(line)
                when (obj.optString("type")) {
                    "meta" -> {
                        meta = Meta(
                            format = obj.optString("format", "pcm"),
                            sampleRate = obj.optInt("sample_rate", 22050),
                            encoding = obj.optString("encoding", "base64"),
                        )
                        onMeta(meta)
                    }
                    "chunk" -> {
                        val data = obj.optString("data")
                        if (data.isNotBlank()) {
                            onChunk(Base64.decode(data, Base64.DEFAULT))
                        }
                    }
                    "error" -> throw RuntimeException(obj.optString("message", "TTS stream error"))
                    "done" -> {
                        if (!obj.isNull("first_packet_ms")) {
                            firstPacketMs = obj.optInt("first_packet_ms")
                        }
                    }
                }
            }
            val m = meta ?: throw RuntimeException("TTS stream: missing meta")
            StreamResult(meta = m, firstPacketMs = firstPacketMs)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "PophieAudio"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun logMetric(firstPacketMs: Int?) {
            if (firstPacketMs != null) {
                Log.d(TAG, "TTS stream first_packet_ms=$firstPacketMs")
            }
        }
    }
}
