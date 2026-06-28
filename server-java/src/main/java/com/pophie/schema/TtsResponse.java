package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** TTS 响应。对应 schemas.py TtsResponse。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TtsResponse {
    private String text;
    private VoiceProsody voice;
    private AudioPayload audio;
}
