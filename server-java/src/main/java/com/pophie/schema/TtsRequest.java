package com.pophie.schema;

import lombok.Data;

/** TTS 请求。对应 schemas.py TtsRequest。 */
@Data
public class TtsRequest {
    private String text;
    private VoiceProsody voice;
    private String voiceId;
}
