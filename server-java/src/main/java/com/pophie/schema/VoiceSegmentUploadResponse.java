package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /api/voice/segments 响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoiceSegmentUploadResponse {
    private long id;
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("stt_text")
    private String sttText;
    @JsonProperty("stt_source")
    private String sttSource;
    @JsonProperty("created_at")
    private String createdAt;
}
