package com.pophie.app.audio

import android.util.Base64
import android.util.Log
import com.pophie.app.data.model.VoiceProsody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端：WS /api/stt/stream
 * 消息：start → chunk* → (commit) → end
 */
class SttStreamClient(
    private val wsUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    interface Listener {
        fun onMeta(sampleRate: Int, turnDetection: Boolean, silenceCommitMs: Int, conversationIdleSec: Int) {}
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String, voice: VoiceProsody?) {}
        fun onSessionEnd(reason: String, message: String) {}
        fun onError(message: String) {}
        fun onClosed() {}
    }

    private var webSocket: WebSocket? = null
    @Volatile private var ready = false
    private var listener: Listener? = null

    fun connect(listener: Listener, sampleRate: Int = 16000, turnDetection: Boolean? = null) {
        this.listener = listener
        ready = false
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendStart(sampleRate, turnDetection)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "STT ws failure", t)
                listener.onError(t.message ?: "STT WebSocket 失败")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                listener.onClosed()
            }
        })
    }

    private fun handleMessage(text: String) {
        val obj = JSONObject(text)
        val l = listener ?: return
        when (obj.optString("type")) {
            "meta" -> {
                l.onMeta(
                    obj.optInt("sample_rate", 16000),
                    obj.optBoolean("turn_detection", true),
                    obj.optInt("silence_commit_ms", 600),
                    obj.optInt("conversation_idle_sec", 60),
                )
            }
            "ready" -> {
                ready = true
                l.onReady()
            }
            "partial" -> l.onPartial(obj.optString("text"))
            "final" -> {
                l.onFinal(obj.optString("text"), parseVoice(obj.optJSONObject("voice")))
            }
            "session_end" -> {
                l.onSessionEnd(
                    obj.optString("reason", "conversation_idle"),
                    obj.optString("message", "会话已结束"),
                )
            }
            "error" -> l.onError(obj.optString("message", "STT 错误"))
        }
    }

    fun sendChunk(pcm: ByteArray) {
        if (!ready || pcm.isEmpty()) return
        val msg = JSONObject()
            .put("type", "chunk")
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
        webSocket?.send(msg.toString())
    }

    fun commit() {
        if (!ready) return
        webSocket?.send(JSONObject().put("type", "commit").toString())
    }

    fun close() {
        ready = false
        try {
            webSocket?.send(JSONObject().put("type", "end").toString())
        } catch (_: Exception) {
        }
        webSocket?.close(1000, "bye")
        webSocket = null
        listener = null
    }

    private fun sendStart(sampleRate: Int, turnDetection: Boolean?) {
        val msg = JSONObject()
            .put("type", "start")
            .put("sample_rate", sampleRate)
        if (turnDetection != null) {
            msg.put("turn_detection", turnDetection)
        }
        webSocket?.send(msg.toString())
    }

    companion object {
        private const val TAG = "PophieStt"

        fun wsUrlFromHttp(baseUrl: String): String =
            baseUrl.trimEnd('/')
                .replace(Regex("^http://"), "ws://")
                .replace(Regex("^https://"), "wss://") + "/api/stt/stream"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        private fun parseVoice(obj: JSONObject?): VoiceProsody? {
            if (obj == null) return null
            return VoiceProsody(
                tone = obj.optString("tone").ifBlank { null },
                intonation = obj.optString("intonation").ifBlank { null },
                speed = obj.optString("speed").ifBlank { null },
            )
        }
    }
}
