package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** POST /api/device/bind 请求体。 */
@Data
public class DeviceBindRequest {

    @JsonProperty("device_id")
    private String deviceId;

    /** 绑定到已有用户（多设备同账号）；省略则新建用户。 */
    @JsonProperty("user_id")
    private String userId;

    /** 可选：沿用已有 robot_id；新设备省略则由服务端签发。 */
    @JsonProperty("robot_id")
    private String robotId;

    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("display_name")
    private String displayName;
}
