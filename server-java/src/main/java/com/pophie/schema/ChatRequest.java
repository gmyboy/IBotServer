package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 聊天请求。对应 schemas.py ChatRequest。 */
@Data
public class ChatRequest {
    @JsonProperty("robot_id")
    private String robotId;
    @JsonProperty("user_id")
    private String userId;
    /** 端侧设备唯一标识；若已绑定则服务端解析 user_id / robot_id。 */
    @JsonProperty("device_id")
    private String deviceId;
    @JsonProperty("session_id")
    private String sessionId;
    private ChatInput input;
}
