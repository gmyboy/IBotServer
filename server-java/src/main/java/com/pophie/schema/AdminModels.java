package com.pophie.schema;

import lombok.Data;

import java.util.Map;

/** Admin 请求体模型，对应 main.py 的 WipeReqModel / RobotPatchModel / ConfigUpdateModel / ServiceUpdateModel。 */
public class AdminModels {

    @Data
    public static class WipeRequest {
        private String scope = "memories";
        private String robotId;
    }

    @Data
    public static class RobotPatchRequest {
        private String displayName;
    }

    @Data
    public static class ConfigUpdateRequest {
        private Map<String, Object> config;
    }

    @Data
    public static class ServiceUpdateRequest {
        private Boolean speechEnabled;
    }

    @Data
    public static class AdminLoginRequest {
        private String token;
    }
}
