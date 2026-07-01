package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 端侧声纹判定快照（客户端上传）。 */
@Data
public class SpeakerSnapshot {
    private String name;
    private String state;
    @JsonProperty("is_owner")
    private boolean owner;
    private float confidence;
    private float margin;
    private String runnerUp;
    private float rawScore;
}
