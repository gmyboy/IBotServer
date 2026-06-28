package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 主人档案写入响应。对应 schemas.py OwnerProfilePutResponse。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnerProfilePutResponse {
    private boolean ok = true;
    private String robotId;
    private OwnerProfile owner;
}
