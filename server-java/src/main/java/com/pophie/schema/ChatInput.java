package com.pophie.schema;

import lombok.Data;

/** 聊天输入。对应 schemas.py ChatInput。skipTts 三态（null/true/false）。 */
@Data
public class ChatInput {
    private String text = "";
    private AudioPayload audio;
    private PerceptionInput perception;
    private String voiceId;
    private Boolean skipTts;
}
