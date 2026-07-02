package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 聊天输入。对应 schemas.py ChatInput。skipTts 三态（null/true/false）。 */
@Data
public class ChatInput {
    private String text = "";
    private AudioPayload audio;
    private PerceptionInput perception;
    @JsonProperty("voice_id")
    private String voiceId;
    @JsonProperty("skip_tts")
    private Boolean skipTts;
    /** 为 true 时 chat/stream 附带服务端 TTS 音频事件（tts_meta/tts_chunk）。 */
    @JsonProperty("server_tts")
    private Boolean serverTts;
}
