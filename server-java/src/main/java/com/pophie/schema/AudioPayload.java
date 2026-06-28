package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 音频载荷。对应 schemas.py AudioPayload（默认 wav/base64/16000）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudioPayload {
    private String format = "wav";
    private String encoding = "base64";
    private int sampleRate = 16000;
    private String data = "";
}
