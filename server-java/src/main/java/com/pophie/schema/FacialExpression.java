package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonValue;

/** 面部表情枚举（7 类），对应 schemas.py FacialExpression。 */
public enum FacialExpression {
    angry("angry"),
    disgust("disgust"),
    fear("fear"),
    happy("happy"),
    neutral("neutral"),
    sad("sad"),
    surprise("surprise");

    private final String value;

    FacialExpression(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** 精确匹配 value（不含别名）。 */
    public static FacialExpression ofValue(String v) {
        for (FacialExpression e : values()) {
            if (e.value.equals(v)) return e;
        }
        return null;
    }
}
