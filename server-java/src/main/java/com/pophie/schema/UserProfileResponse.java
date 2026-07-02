package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserProfileResponse {

    @JsonProperty("user_id")
    private final String userId;

    @JsonProperty("display_name")
    private final String displayName;

    @JsonProperty("nickname")
    private final String nickname;

    @JsonProperty("gender")
    private final String gender;

    @JsonProperty("birthday")
    private final String birthday;

    @JsonProperty("avatar_url")
    private final String avatarUrl;

    @JsonProperty("voice_enrolled")
    private final boolean voiceEnrolled;

    @JsonProperty("created_at")
    private final String createdAt;

    @JsonProperty("updated_at")
    private final String updatedAt;
}
