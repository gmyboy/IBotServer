package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** POST /api/voice/segments 请求体。 */
@Data
public class VoiceSegmentUploadRequest {
    @JsonProperty("robot_id")
    private String robotId;
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("client_segment_id")
    private String clientSegmentId;
    @JsonProperty("duration_ms")
    private long durationMs;
    @JsonProperty("sample_rate")
    private int sampleRate;
    private AudioPayload audio;
    private SpeakerSnapshot speaker;
    @JsonProperty("stt_text")
    private String sttText;
    @JsonProperty("stt_source")
    private String sttSource = "none";
    private List<Float> embedding;
    private Map<String, Object> metadata;
    @JsonProperty("server_stt_if_empty")
    private boolean serverSttIfEmpty = true;
}
