package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 体姿态（预留，当前不实现）。对应 schemas.py PostureAction。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostureAction {
    private String type;
    private Map<String, Object> params;
}
