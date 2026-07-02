package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** 机器人配置更新请求。所有字段可选，仅更新传入的字段。 */
@Getter
@Setter
public class RobotConfigRequest {

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("persona")
    private String persona;

    @JsonProperty("voice_id")
    private String voiceId;

    @JsonProperty("voice_style")
    private String voiceStyle;

    @JsonProperty("greeting")
    private String greeting;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    @JsonProperty("language")
    private String language;

    @JsonProperty("personality_tags")
    private String personalityTags;

    @JsonProperty("system_prompt")
    private String systemPrompt;
}
