package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** PATCH /api/voice/segments/{id}/stt */
@Data
public class VoiceSegmentSttPatchRequest {
    @JsonProperty("stt_text")
    private String sttText;
    @JsonProperty("stt_source")
    private String sttSource = "realtime";
}
