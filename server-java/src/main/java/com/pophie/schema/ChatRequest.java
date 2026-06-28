package com.pophie.schema;

import lombok.Data;

/** 聊天请求。对应 schemas.py ChatRequest。 */
@Data
public class ChatRequest {
    private String robotId;
    private String userId;
    private String sessionId;
    private ChatInput input;
}
