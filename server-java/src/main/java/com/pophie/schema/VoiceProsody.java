package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 语音副通道：语气 / 语调 / 语速。对应 schemas.py VoiceProsody。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoiceProsody {
    private String tone;
    private String intonation;
    private String speed;
}
