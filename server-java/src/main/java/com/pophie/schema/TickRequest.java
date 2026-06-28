package com.pophie.schema;

import lombok.Data;

import java.util.Map;

/** 主动 tick 请求。对应 main.py TickReqModel。 */
@Data
public class TickRequest {
    private String robotId;
    private String userId;
    private String sessionId;
    private Map<String, Object> signal;
}
