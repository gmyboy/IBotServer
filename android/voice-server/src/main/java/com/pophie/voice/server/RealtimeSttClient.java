package com.pophie.voice.server;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** WebSocket 实时 STT：边说边收 partial，commit 后收 final。 */
public final class RealtimeSttClient {

    private static final String TAG = "VoiceServerStt";
    private static final int CHUNK_BYTES = 1280;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private WebSocket webSocket;
    private Listener listener;
    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private volatile boolean connected;
    private volatile boolean utteranceOpen;

    public interface Listener {
        void onConnected();

        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);

        void onClosed();
    }

    public boolean isConnected() {
        return connected;
    }

    public void connect(String httpBaseUrl, Listener listener) {
        close();
        this.listener = listener;
        String wsUrl = toWsUrl(httpBaseUrl) + "/api/stt/stream";
        Request req = new Request.Builder().url(wsUrl).build();
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                if (RealtimeSttClient.this.listener != null) {
                    RealtimeSttClient.this.listener.onConnected();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleMessage(bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                utteranceOpen = false;
                if (RealtimeSttClient.this.listener != null) {
                    RealtimeSttClient.this.listener.onClosed();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                utteranceOpen = false;
                String msg = t.getMessage() == null ? "WebSocket 失败" : t.getMessage();
                Log.e(TAG, "ws failure", t);
                if (RealtimeSttClient.this.listener != null) {
                    RealtimeSttClient.this.listener.onError(msg);
                }
            }
        });
    }

    public void beginUtterance(int sampleRate) {
        if (!connected || webSocket == null) return;
        pcmBuffer.reset();
        utteranceOpen = true;
        sendJson(obj -> {
            obj.put("type", "start");
            obj.put("sample_rate", sampleRate);
        });
    }

    public void feedPcm(short[] pcm16) {
        if (!connected || !utteranceOpen || pcm16 == null || pcm16.length == 0) return;
        byte[] bytes = shortsToLeBytes(pcm16);
        synchronized (pcmBuffer) {
            pcmBuffer.write(bytes, 0, bytes.length);
            while (pcmBuffer.size() >= CHUNK_BYTES) {
                byte[] chunk = pcmBuffer.toByteArray();
                byte[] send = new byte[CHUNK_BYTES];
                System.arraycopy(chunk, 0, send, 0, CHUNK_BYTES);
                int remain = chunk.length - CHUNK_BYTES;
                pcmBuffer.reset();
                if (remain > 0) {
                    pcmBuffer.write(chunk, CHUNK_BYTES, remain);
                }
                flushChunk(send);
            }
        }
    }

    public void commitUtterance() {
        if (!connected || webSocket == null) return;
        synchronized (pcmBuffer) {
            if (pcmBuffer.size() > 0) {
                flushChunk(pcmBuffer.toByteArray());
                pcmBuffer.reset();
            }
        }
        if (utteranceOpen) {
            sendJson(obj -> obj.put("type", "commit"));
            utteranceOpen = false;
        }
    }

    public void close() {
        if (webSocket != null) {
            try {
                sendJson(obj -> obj.put("type", "close"));
            } catch (Exception ignored) {
            }
            webSocket.close(1000, "bye");
            webSocket = null;
        }
        connected = false;
        utteranceOpen = false;
        pcmBuffer.reset();
    }

    private void flushChunk(byte[] pcm) {
        sendJson(obj -> {
            obj.put("type", "audio");
            obj.put("data", Base64.encodeToString(pcm, Base64.NO_WRAP));
        });
    }

    private void handleMessage(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");
            Listener l = listener;
            if (l == null) return;
            switch (type) {
                case "partial" -> {
                    String p = obj.optString("text", "");
                    if (!p.isEmpty()) l.onPartial(p);
                }
                case "final" -> l.onFinal(obj.optString("text", ""));
                case "error" -> l.onError(obj.optString("message", "STT error"));
                default -> { }
            }
        } catch (Exception e) {
            Log.w(TAG, "bad message: " + text, e);
        }
    }

    private interface JsonBuilder {
        void build(JSONObject obj) throws Exception;
    }

    private void sendJson(JsonBuilder builder) {
        if (webSocket == null) return;
        try {
            JSONObject obj = new JSONObject();
            builder.build(obj);
            webSocket.send(obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "send failed", e);
        }
    }

    private static byte[] shortsToLeBytes(short[] pcm) {
        ByteBuffer buf = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) buf.putShort(s);
        return buf.array();
    }

    static String toWsUrl(String httpBase) {
        String u = httpBase == null ? "" : httpBase.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.startsWith("https://")) return "wss://" + u.substring(8);
        if (u.startsWith("http://")) return "ws://" + u.substring(7);
        return "ws://" + u;
    }
}
