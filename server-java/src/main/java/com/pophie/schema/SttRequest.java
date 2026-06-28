package com.pophie.schema;

import lombok.Data;

/** STT 请求。对应 schemas.py SttRequest。 */
@Data
public class SttRequest {
    private AudioPayload audio;
}
