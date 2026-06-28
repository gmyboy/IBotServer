package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** STT 结果。对应 schemas.py SttResult。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SttResult {
    private String text = "";
    private VoiceProsody voice;
}
