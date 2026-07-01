package com.pophie.voice.server;

import android.util.Base64;

import com.pophie.voice.SpeakerInfo;
import com.pophie.voice.VoiceSegment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Pophie HTTP API：健康检查、会话、chat/stream。 */
public final class PophieApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private final String robotId;
    private final String userId;
    private String baseUrl;

    public PophieApiClient(VoiceServerConfig config) {
        this.robotId = config.robotId;
        this.userId = config.userId;
        setBaseUrl(config.baseUrl);
    }

    public void setBaseUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            baseUrl = "http://10.0.2.2:8000/";
            return;
        }
        if (!u.endsWith("/")) u += "/";
        baseUrl = u;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public HealthInfo checkHealth() throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "api/health")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            JSONObject json = new JSONObject(body);
            return new HealthInfo(true, json.optBoolean("speech_enabled", false), body);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public SessionInfo newSession() throws IOException {
        String q = "robot_id=" + robotId + "&user_id=" + userId;
        Request req = new Request.Builder()
                .url(baseUrl + "api/session/new?" + q)
                .post(RequestBody.create("", JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            JSONObject json = new JSONObject(body);
            return new SessionInfo(
                    json.optString("session_id", ""),
                    json.optString("robot_id", robotId),
                    json.optString("user_id", userId));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public ChatStreamResult chatStream(String sessionId, byte[] wavBytes, int sampleRate,
                                       SpeakListener onSpeak) throws IOException {
        if (wavBytes == null || wavBytes.length == 0) {
            throw new IOException("空音频");
        }
        try {
            JSONObject audio = new JSONObject();
            audio.put("format", "wav");
            audio.put("encoding", "base64");
            audio.put("sample_rate", sampleRate);
            audio.put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP));

            JSONObject input = new JSONObject();
            input.put("text", "");
            input.put("audio", audio);
            input.put("skip_tts", true);

            JSONObject root = new JSONObject();
            root.put("robot_id", robotId);
            root.put("user_id", userId);
            if (sessionId != null && !sessionId.isEmpty()) {
                root.put("session_id", sessionId);
            }
            root.put("input", input);

            Request req = new Request.Builder()
                    .url(baseUrl + "api/chat/stream")
                    .post(RequestBody.create(root.toString(), JSON))
                    .build();

            StringBuilder reply = new StringBuilder();
            ChatStreamResult result = null;

            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String errBody = resp.body() != null ? resp.body().string() : "";
                    throw new IOException("HTTP " + resp.code() + ": " + errBody);
                }
                if (resp.body() == null) {
                    throw new IOException("空响应体");
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JSONObject obj = new JSONObject(line);
                    String type = obj.optString("type", "");
                    switch (type) {
                        case "speak" -> {
                            String text = obj.optString("text", "");
                            if (!text.isEmpty()) {
                                reply.append(text);
                                if (onSpeak != null) onSpeak.onSpeak(text);
                            }
                        }
                        case "error" ->
                                throw new IOException(obj.optString("message", "chat/stream error"));
                        case "done" -> {
                            JSONObject response = obj.optJSONObject("response");
                            if (response == null) {
                                throw new IOException("chat/stream: missing response");
                            }
                            result = parseDone(response, reply.toString());
                        }
                        default -> { }
                    }
                }
            }
            if (result == null) {
                throw new IOException("chat/stream: missing done");
            }
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static ChatStreamResult parseDone(JSONObject response, String streamedReply)
            throws JSONException {
        String sessionId = response.optString("session_id", "");
        String sttText = "";
        String sttVoiceHint = "";
        JSONObject stt = response.optJSONObject("stt");
        if (stt != null) {
            sttText = stt.optString("text", "");
            JSONObject voice = stt.optJSONObject("voice");
            if (voice != null) {
                sttVoiceHint = String.format(" [%s/%s/%s]",
                        voice.optString("tone", ""),
                        voice.optString("intonation", ""),
                        voice.optString("speed", "")).trim();
            }
        }
        String outputText = streamedReply;
        JSONObject output = response.optJSONObject("output");
        if (output != null) {
            String finalText = output.optString("text", "");
            if (!finalText.isEmpty()) {
                outputText = finalText;
            }
        }
        return new ChatStreamResult(sessionId, sttText, sttVoiceHint, outputText);
    }

    /** 上传语音段流水（主人/非主人声纹、音频、STT 等）。 */
    public SegmentLogResult uploadVoiceSegment(String sessionId, VoiceSegment seg, String sttText,
                                               VoiceServerConfig cfg, String gateMode) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("robot_id", robotId);
            root.put("user_id", userId);
            if (sessionId != null && !sessionId.isEmpty()) {
                root.put("session_id", sessionId);
            }
            root.put("client_segment_id", seg.durationMs + "-" + System.currentTimeMillis());
            root.put("duration_ms", seg.durationMs);
            root.put("sample_rate", seg.sampleRate);
            root.put("server_stt_if_empty", cfg.serverSttIfEmpty);

            byte[] wav = seg.toWav();
            if (wav.length > 0 && (cfg.includeAudioInLog || cfg.serverSttIfEmpty)) {
                JSONObject audio = new JSONObject();
                audio.put("format", "wav");
                audio.put("encoding", "base64");
                audio.put("sample_rate", seg.sampleRate);
                audio.put("data", Base64.encodeToString(wav, Base64.NO_WRAP));
                root.put("audio", audio);
            }

            SpeakerInfo spk = seg.speaker;
            if (spk != null) {
                JSONObject speaker = new JSONObject();
                speaker.put("name", spk.name);
                speaker.put("state", spk.state != null ? spk.state.name() : "UNKNOWN");
                speaker.put("is_owner", spk.isOwner);
                speaker.put("confidence", spk.confidence);
                speaker.put("margin", spk.margin);
                speaker.put("runner_up", spk.runnerUp);
                speaker.put("raw_score", spk.rawScore);
                root.put("speaker", speaker);
            }

            if (sttText != null && !sttText.isEmpty()) {
                root.put("stt_text", sttText);
                root.put("stt_source", "realtime");
            } else {
                root.put("stt_source", "none");
            }

            if (cfg.includeEmbeddingInLog && seg.embedding != null && seg.embedding.length > 0) {
                JSONArray emb = new JSONArray();
                for (float v : seg.embedding) emb.put(v);
                root.put("embedding", emb);
            }

            JSONObject meta = new JSONObject();
            meta.put("gate_mode", gateMode);
            root.put("metadata", meta);

            Request req = new Request.Builder()
                    .url(baseUrl + "api/voice/segments")
                    .post(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JSONObject json = new JSONObject(body);
                return new SegmentLogResult(
                        json.optLong("id", 0),
                        json.optString("session_id", sessionId),
                        json.optString("stt_text", ""),
                        json.optString("stt_source", ""),
                        json.optString("created_at", ""));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 补写流水 STT（final 晚于 POST 入库时）。 */
    public void patchVoiceSegmentStt(long logId, String sttText) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("stt_text", sttText);
            root.put("stt_source", "realtime");
            Request req = new Request.Builder()
                    .url(baseUrl + "api/voice/segments/" + logId + "/stt")
                    .patch(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public interface SpeakListener {
        void onSpeak(String text);
    }

    public static final class HealthInfo {
        public final boolean ok;
        public final boolean speechEnabled;
        public final String raw;

        HealthInfo(boolean ok, boolean speechEnabled, String raw) {
            this.ok = ok;
            this.speechEnabled = speechEnabled;
            this.raw = raw;
        }
    }

    public static final class SessionInfo {
        public final String sessionId;
        public final String robotId;
        public final String userId;

        SessionInfo(String sessionId, String robotId, String userId) {
            this.sessionId = sessionId;
            this.robotId = robotId;
            this.userId = userId;
        }
    }

    public static final class SegmentLogResult {
        public final long id;
        public final String sessionId;
        public final String sttText;
        public final String sttSource;
        public final String createdAt;

        SegmentLogResult(long id, String sessionId, String sttText, String sttSource, String createdAt) {
            this.id = id;
            this.sessionId = sessionId;
            this.sttText = sttText;
            this.sttSource = sttSource;
            this.createdAt = createdAt;
        }
    }

    public static final class ChatStreamResult {
        public final String sessionId;
        public final String sttText;
        public final String sttVoiceHint;
        public final String replyText;

        ChatStreamResult(String sessionId, String sttText, String sttVoiceHint, String replyText) {
            this.sessionId = sessionId;
            this.sttText = sttText;
            this.sttVoiceHint = sttVoiceHint;
            this.replyText = replyText;
        }
    }
}
