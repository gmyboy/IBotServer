package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** 用户档案更新请求。所有字段可选，仅更新传入的字段。 */
@Getter
@Setter
public class UserProfileRequest {

    @JsonProperty("nickname")
    private String nickname;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("birthday")
    private String birthday;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    @JsonProperty("voice_data")
    private String voiceData;

    @JsonProperty("voice_data_format")
    private String voiceDataFormat;

    @JsonProperty("voice_data_sample_rate")
    private Integer voiceDataSampleRate;

    @JsonProperty("voice_enrolled")
    private Boolean voiceEnrolled;
}
