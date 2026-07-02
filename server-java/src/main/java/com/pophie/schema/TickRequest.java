package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/** 主动 tick 请求。对应 main.py TickReqModel。 */
@Data
public class TickRequest {
    @JsonProperty("robot_id")
    private String robotId;
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("session_id")
    private String sessionId;
    private Map<String, Object> signal;
}
