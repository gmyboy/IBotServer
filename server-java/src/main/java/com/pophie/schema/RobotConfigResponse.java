package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RobotConfigResponse {

    @JsonProperty("robot_id")
    private final String robotId;

    @JsonProperty("display_name")
    private final String displayName;

    @JsonProperty("persona")
    private final String persona;

    @JsonProperty("voice_id")
    private final String voiceId;

    @JsonProperty("voice_style")
    private final String voiceStyle;

    @JsonProperty("greeting")
    private final String greeting;

    @JsonProperty("avatar_url")
    private final String avatarUrl;

    @JsonProperty("language")
    private final String language;

    @JsonProperty("personality_tags")
    private final String personalityTags;

    @JsonProperty("system_prompt")
    private final String systemPrompt;

    @JsonProperty("created_at")
    private final String createdAt;

    @JsonProperty("last_seen_at")
    private final String lastSeenAt;

    @JsonProperty("updated_at")
    private final String updatedAt;
}
