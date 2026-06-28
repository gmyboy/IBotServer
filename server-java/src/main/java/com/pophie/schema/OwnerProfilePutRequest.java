package com.pophie.schema;

import lombok.Data;

/** 主人档案写入请求。对应 schemas.py OwnerProfilePutRequest。 */
@Data
public class OwnerProfilePutRequest {
    private OwnerProfile owner;
}
