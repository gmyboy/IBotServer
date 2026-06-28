package com.pophie.schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 聊天响应。对应 schemas.py ChatResponse。 */
@Data
public class ChatResponse {
    private String robotId;
    private String userId = "default";
    private String sessionId;
    private RobotOutput output;
    private SttResult stt;
    private List<Object> memoryFlow = new ArrayList<>();
    private List<Object> l1Frames = new ArrayList<>();
    private List<Object> recalled = new ArrayList<>();
    private List<Object> scheduledReminders = new ArrayList<>();
}
