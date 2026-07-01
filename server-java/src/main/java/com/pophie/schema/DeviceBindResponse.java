package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /api/device/bind 响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceBindResponse {

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("robot_id")
    private String robotId;

    @JsonProperty("new_user")
    private boolean newUser;

    @JsonProperty("new_device")
    private boolean newDevice;
}
