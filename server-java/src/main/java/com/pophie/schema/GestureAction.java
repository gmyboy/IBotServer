package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 手势（预留，当前不实现）。对应 schemas.py GestureAction。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GestureAction {
    private String type;
    private Map<String, Object> params;
}
