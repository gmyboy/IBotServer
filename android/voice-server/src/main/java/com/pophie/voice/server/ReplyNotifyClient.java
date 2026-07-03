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

import java.util.concurrent.atomic.AtomicLong;

/**
 * 接收服务端主动回复 + TTS 音频。支持两种模式：
 * <ul>
 *   <li>LONG_NOTIFY（默认）：连接 /api/reply/notify 保持长连接</li>
 *   <li>PULL：连接 /api/reply/pull 短连接，拉取完毕后服务端自动关闭</li>
 * </ul>
 */
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
    private volatile Mode mode = Mode.LONG_NOTIFY;
    private final AtomicBoolean pullComplete = new AtomicBoolean(false);
    private final AtomicLong connId = new AtomicLong(0);

    public enum Mode { LONG_NOTIFY, PULL }

    public interface EventHandler {
        void onReady();
        void onReplyEvent(String phase, String text, int seq, String source);
        void onTtsMeta(int seq, String format, int sampleRate);
        void onTtsChunk(int seq, byte[] audio);
        void onError(String message);
        void onClosed();
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean connectIfNeeded(String httpBase, String robotId, String userId, String sessionId,
                                   Mode mode, EventHandler handler, long readyTimeoutMs) {
        String key = buildKey(httpBase, robotId, userId, sessionId, mode);
        this.handler = handler;
        this.mode = mode;
        if (key.equals(connectedKey) && webSocket != null && ready.get()) {
            return true;
        }
        connect(httpBase, robotId, userId, sessionId, mode, handler);
        return awaitReady(readyTimeoutMs);
    }

    public void connect(String httpBase, String robotId, String userId, String sessionId,
                        Mode mode, EventHandler handler) {
        close();
        final long myConnId = connId.incrementAndGet();
        this.handler = handler;
        this.mode = mode;
        this.pullComplete.set(false);
        connectedKey = buildKey(httpBase, robotId, userId, sessionId, mode);
        ready.set(false);
        readyLatch = new CountDownLatch(1);
        String wsBase = RealtimeSttClient.toWsUrl(httpBase);
        String path = mode == Mode.PULL ? "/api/reply/pull" : "/api/reply/notify";
        StringBuilder url = new StringBuilder(wsBase)
                .append(path)
                .append("?robot_id=").append(robotId)
                .append("&user_id=").append(userId);
        if (sessionId != null && !sessionId.isEmpty()) {
            url.append("&session_id=").append(sessionId);
        }
        if (mode == Mode.PULL) {
            url.append("&mode=pull");
        }
        Request req = new Request.Builder().url(url.toString()).build();
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (myConnId != connId.get()) return;
                Log.i(TAG, "connected mode=" + mode);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (myConnId != connId.get()) return;
                handle(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (myConnId != connId.get()) return;
                handle(bytes.utf8());
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (myConnId != connId.get()) return;
                ready.set(false);
                connectedKey = "";
                readyLatch.countDown();
                EventHandler h = ReplyNotifyClient.this.handler;
                if (h != null) h.onError(t.getMessage() == null ? "notify ws failed" : t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (myConnId != connId.get()) return;
                ready.set(false);
                connectedKey = "";
                if (mode == Mode.PULL) {
                    pullComplete.set(true);
                }
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

    public boolean isPullComplete() {
        return pullComplete.get();
    }

    public void close() {
        connId.incrementAndGet();
        connectedKey = "";
        ready.set(false);
        pullComplete.set(false);
        readyLatch.countDown();
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try { ws.close(1000, "bye"); } catch (Exception ignored) {}
        }
    }

    private static String buildKey(String httpBase, String robotId, String userId,
                                    String sessionId, Mode mode) {
        String sid = sessionId == null ? "" : sessionId;
        return httpBase + "|" + robotId + "|" + userId + "|" + sid + "|" + mode.name();
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
