package com.pophie.voice.server;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** 订阅服务端 {@code /api/reply/notify}，接收主动回复 + TTS 音频。 */
public final class ReplyNotifyClient {

    private static final String TAG = "ReplyNotify";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private WebSocket webSocket;
    private EventHandler handler;
    private volatile String connectedKey = "";
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);

    public interface EventHandler {
        void onReady();
        void onReplyEvent(String phase, String text, int seq, String source);
        void onTtsMeta(int seq, String format, int sampleRate);
        void onTtsChunk(int seq, byte[] audio);
        void onError(String message);
        void onClosed();
    }

    /** 已连接且收到服务端 ready 时为 true。 */
    public boolean isReady() {
        return ready.get();
    }

    /**
     * 若已连接同一端点则保持连接；否则重连。
     * @return 是否在 timeout 内收到 ready
     */
    public boolean connectIfNeeded(String httpBase, String robotId, String userId, String sessionId,
                                   EventHandler handler, long readyTimeoutMs) {
        String key = buildKey(httpBase, robotId, userId, sessionId);
        this.handler = handler;
        if (key.equals(connectedKey) && webSocket != null && ready.get()) {
            return true;
        }
        connect(httpBase, robotId, userId, sessionId, handler);
        return awaitReady(readyTimeoutMs);
    }

    public void connect(String httpBase, String robotId, String userId, String sessionId,
                        EventHandler handler) {
        close();
        this.handler = handler;
        connectedKey = buildKey(httpBase, robotId, userId, sessionId);
        ready.set(false);
        readyLatch = new CountDownLatch(1);
        String wsBase = RealtimeSttClient.toWsUrl(httpBase);
        StringBuilder url = new StringBuilder(wsBase)
                .append("/api/reply/notify?robot_id=")
                .append(robotId)
                .append("&user_id=")
                .append(userId);
        if (sessionId != null && !sessionId.isEmpty()) {
            url.append("&session_id=").append(sessionId);
        }
        Request req = new Request.Builder().url(url.toString()).build();
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handle(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handle(bytes.utf8());
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                ready.set(false);
                connectedKey = "";
                readyLatch.countDown();
                EventHandler h = ReplyNotifyClient.this.handler;
                if (h != null) h.onError(t.getMessage() == null ? "notify ws failed" : t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                ready.set(false);
                connectedKey = "";
                EventHandler h = ReplyNotifyClient.this.handler;
                if (h != null) h.onClosed();
            }
        });
    }

    public boolean awaitReady(long timeoutMs) {
        if (ready.get()) return true;
        try {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && ready.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void close() {
        connectedKey = "";
        ready.set(false);
        readyLatch.countDown();
        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
    }

    private static String buildKey(String httpBase, String robotId, String userId, String sessionId) {
        String sid = sessionId == null ? "" : sessionId;
        return httpBase + "|" + robotId + "|" + userId + "|" + sid;
    }

    private void handle(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");
            EventHandler h = handler;
            if (h == null) return;
            switch (type) {
                case "ready" -> {
                    ready.set(true);
                    readyLatch.countDown();
                    h.onReady();
                }
                case "reply" -> {
                    String phase = obj.optString("phase", "");
                    int seq = obj.optInt("seq", 0);
                    h.onReplyEvent(phase, obj.optString("text", ""),
                            seq, obj.optString("source", ""));
                }
                case "tts_meta" -> h.onTtsMeta(obj.optInt("seq", 0),
                        obj.optString("format", "pcm"), obj.optInt("sample_rate", 22050));
                case "tts_chunk" -> {
                    int seq = obj.optInt("seq", 0);
                    String data = obj.optString("data", "");
                    if (!data.isEmpty()) {
                        h.onTtsChunk(seq, Base64.decode(data, Base64.DEFAULT));
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            Log.w(TAG, "bad msg: " + text, e);
        }
    }
}
