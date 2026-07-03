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

    private String robotId;
    private String userId;
    private String deviceId;
    private String baseUrl;

    public PophieApiClient(VoiceServerConfig config) {
        this.robotId = config.robotId;
        this.userId = config.userId;
        this.deviceId = config.deviceId == null ? "" : config.deviceId;
        setBaseUrl(config.baseUrl);
    }

    public String getRobotId() {
        return robotId;
    }

    public String getUserId() {
        return userId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String id) {
        deviceId = id == null ? "" : id.trim();
    }

    public void applyIdentity(String newRobotId, String newUserId) {
        if (newRobotId != null && !newRobotId.isEmpty()) robotId = newRobotId;
        if (newUserId != null && !newUserId.isEmpty()) userId = newUserId;
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

    /** 设备绑定：以 device_id 关联用户，更新本地 robot_id / user_id。 */
    public BindInfo bindDevice() throws IOException {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IOException("device_id 未配置");
        }
        try {
            JSONObject root = new JSONObject();
            root.put("device_id", deviceId);
            if (userId != null && !userId.isEmpty() && !"demo".equals(userId)) {
                root.put("user_id", userId);
            }
            if (robotId != null && !robotId.isEmpty() && !"default".equals(robotId)) {
                root.put("robot_id", robotId);
            }
            Request req = new Request.Builder()
                    .url(baseUrl + "api/device/bind")
                    .post(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JSONObject json = new JSONObject(body);
                String rid = json.optString("robot_id", robotId);
                String uid = json.optString("user_id", userId);
                applyIdentity(rid, uid);
                return new BindInfo(deviceId, rid, uid,
                        json.optBoolean("new_user", false),
                        json.optBoolean("new_device", false));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public SessionInfo newSession() throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "api/sessions")
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
                    robotId,
                    userId);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** 联调：触发服务端经 reply/notify WebSocket 推送测试回复（不按 session 过滤）。 */
    public void testReplyPush(String text) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("robot_id", robotId);
            root.put("user_id", userId);
            if (text != null && !text.isEmpty()) {
                root.put("text", text);
            }
            Request req = new Request.Builder()
                    .url(baseUrl + "api/reply/test")
                    .post(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public ChatStreamResult chatStream(String sessionId, byte[] wavBytes, int sampleRate,
                                       ChatStreamHandler handler, VoiceServerConfig cfg) throws IOException {
        if (cfg == null) {
            throw new IOException("VoiceServerConfig required");
        }
        return chatStreamInternal(sessionId, wavBytes, sampleRate, handler, cfg);
    }

    private ChatStreamResult chatStreamInternal(String sessionId, byte[] wavBytes, int sampleRate,
                                                ChatStreamHandler handler,
                                                VoiceServerConfig cfg) throws IOException {
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
            input.put("server_tts", cfg.serverReplyTts);

            JSONObject root = new JSONObject();
            root.put("robot_id", robotId);
            root.put("user_id", userId);
            if (deviceId != null && !deviceId.isEmpty()) {
                root.put("device_id", deviceId);
            }
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
                        case "reply" -> {
                            if (handler != null) {
                                handler.onReply(obj.optString("phase", ""),
                                        obj.optString("text", ""),
                                        obj.optInt("seq", 0),
                                        obj.optString("source", "chat"));
                            }
                        }
                        case "speak" -> {
                            String text = obj.optString("text", "");
                            int seq = obj.optInt("seq", 0);
                            if (!text.isEmpty()) {
                                reply.append(text);
                                if (handler != null) handler.onSpeak(text, seq);
                            }
                        }
                        case "tts_meta" -> {
                            if (handler != null) {
                                handler.onTtsMeta(obj.optInt("seq", 0),
                                        obj.optString("format", "pcm"),
                                        obj.optInt("sample_rate", 22050));
                            }
                        }
                        case "tts_chunk" -> {
                            if (handler != null) {
                                String data = obj.optString("data", "");
                                if (!data.isEmpty()) {
                                    handler.onTtsChunk(obj.optInt("seq", 0),
                                            Base64.decode(data, Base64.DEFAULT));
                                }
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
            if (deviceId != null && !deviceId.isEmpty()) {
                root.put("device_id", deviceId);
            }
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

    /** chat/stream NDJSON 事件（后台线程回调）。 */
    public interface ChatStreamHandler {
        default void onReply(String phase, String text, int seq, String source) {}
        default void onSpeak(String text, int seq) {}
        default void onTtsMeta(int seq, String format, int sampleRate) {}
        default void onTtsChunk(int seq, byte[] audio) {}
    }

    public interface SpeakListener {
        void onSpeak(String text);
    }

    public static final class BindInfo {
        public final String deviceId;
        public final String robotId;
        public final String userId;
        public final boolean newUser;
        public final boolean newDevice;

        BindInfo(String deviceId, String robotId, String userId, boolean newUser, boolean newDevice) {
            this.deviceId = deviceId;
            this.robotId = robotId;
            this.userId = userId;
            this.newUser = newUser;
            this.newDevice = newDevice;
        }
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

    public UserProfileResult getUserProfile(String userId) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "api/users/" + userId + "/profile")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            JSONObject json = new JSONObject(body);
            return new UserProfileResult(
                    json.optString("user_id", ""),
                    json.optString("display_name", ""),
                    json.optString("nickname", ""),
                    json.optString("gender", ""),
                    json.optString("birthday", ""),
                    json.optString("avatar_url", ""),
                    json.optBoolean("voice_enrolled", false),
                    json.optString("created_at", ""),
                    json.optString("updated_at", "")
            );
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public UserProfileResult updateUserProfile(String userId, String nickname, String gender,
                                                String birthday, String avatarUrl) throws IOException {
        try {
            JSONObject root = new JSONObject();
            if (nickname != null) root.put("nickname", nickname);
            if (gender != null) root.put("gender", gender);
            if (birthday != null) root.put("birthday", birthday);
            if (avatarUrl != null) root.put("avatar_url", avatarUrl);
            Request req = new Request.Builder()
                    .url(baseUrl + "api/users/" + userId + "/profile")
                    .put(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JSONObject json = new JSONObject(body);
                return new UserProfileResult(
                        json.optString("user_id", ""),
                        json.optString("display_name", ""),
                        json.optString("nickname", ""),
                        json.optString("gender", ""),
                        json.optString("birthday", ""),
                        json.optString("avatar_url", ""),
                        json.optBoolean("voice_enrolled", false),
                        json.optString("created_at", ""),
                        json.optString("updated_at", "")
                );
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public RobotConfigResult getRobotConfig(String robotId) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "api/robots/" + robotId + "/config")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            JSONObject json = new JSONObject(body);
            return new RobotConfigResult(
                    json.optString("robot_id", ""),
                    json.optString("display_name", ""),
                    json.optString("persona", ""),
                    json.optString("voice_id", ""),
                    json.optString("voice_style", ""),
                    json.optString("greeting", ""),
                    json.optString("avatar_url", ""),
                    json.optString("language", ""),
                    json.optString("personality_tags", ""),
                    json.optString("system_prompt", ""),
                    json.optString("created_at", ""),
                    json.optString("last_seen_at", ""),
                    json.optString("updated_at", "")
            );
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public RobotConfigResult updateRobotConfig(String robotId, String displayName, String persona,
                                                String voiceId, String voiceStyle, String greeting,
                                                String avatarUrl) throws IOException {
        try {
            JSONObject root = new JSONObject();
            if (displayName != null) root.put("display_name", displayName);
            if (persona != null) root.put("persona", persona);
            if (voiceId != null) root.put("voice_id", voiceId);
            if (voiceStyle != null) root.put("voice_style", voiceStyle);
            if (greeting != null) root.put("greeting", greeting);
            if (avatarUrl != null) root.put("avatar_url", avatarUrl);
            Request req = new Request.Builder()
                    .url(baseUrl + "api/robots/" + robotId + "/config")
                    .put(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JSONObject json = new JSONObject(body);
                return new RobotConfigResult(
                        json.optString("robot_id", ""),
                        json.optString("display_name", ""),
                        json.optString("persona", ""),
                        json.optString("voice_id", ""),
                        json.optString("voice_style", ""),
                        json.optString("greeting", ""),
                        json.optString("avatar_url", ""),
                        json.optString("language", ""),
                        json.optString("personality_tags", ""),
                        json.optString("system_prompt", ""),
                        json.optString("created_at", ""),
                        json.optString("last_seen_at", ""),
                        json.optString("updated_at", "")
                );
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public static final class UserProfileResult {
        public final String userId;
        public final String displayName;
        public final String nickname;
        public final String gender;
        public final String birthday;
        public final String avatarUrl;
        public final boolean voiceEnrolled;
        public final String createdAt;
        public final String updatedAt;

        UserProfileResult(String userId, String displayName, String nickname, String gender,
                          String birthday, String avatarUrl, boolean voiceEnrolled,
                          String createdAt, String updatedAt) {
            this.userId = userId;
            this.displayName = displayName;
            this.nickname = nickname;
            this.gender = gender;
            this.birthday = birthday;
            this.avatarUrl = avatarUrl;
            this.voiceEnrolled = voiceEnrolled;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static final class RobotConfigResult {
        public final String robotId;
        public final String displayName;
        public final String persona;
        public final String voiceId;
        public final String voiceStyle;
        public final String greeting;
        public final String avatarUrl;
        public final String language;
        public final String personalityTags;
        public final String systemPrompt;
        public final String createdAt;
        public final String lastSeenAt;
        public final String updatedAt;

        RobotConfigResult(String robotId, String displayName, String persona, String voiceId,
                          String voiceStyle, String greeting, String avatarUrl, String language,
                          String personalityTags, String systemPrompt, String createdAt,
                          String lastSeenAt, String updatedAt) {
            this.robotId = robotId;
            this.displayName = displayName;
            this.persona = persona;
            this.voiceId = voiceId;
            this.voiceStyle = voiceStyle;
            this.greeting = greeting;
            this.avatarUrl = avatarUrl;
            this.language = language;
            this.personalityTags = personalityTags;
            this.systemPrompt = systemPrompt;
            this.createdAt = createdAt;
            this.lastSeenAt = lastSeenAt;
            this.updatedAt = updatedAt;
        }
    }
}
