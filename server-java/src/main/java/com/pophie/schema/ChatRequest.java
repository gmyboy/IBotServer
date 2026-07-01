package com.pophie.schema;

import lombok.Data;

/** 聊天请求。对应 schemas.py ChatRequest。 */
@Data
public class ChatRequest {
    private String robotId;
    private String userId;
    /** 端侧设备唯一标识；若已绑定则服务端解析 user_id / robot_id。 */
    private String deviceId;
    private String sessionId;
    private ChatInput input;
}
