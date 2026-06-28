package com.pophie.schema;

import com.fasterxml.jackson.annotation.JsonValue;

/** 虚拟宠物有限状态机（FSM）9 态，对应 schemas.py RobotState。 */
public enum RobotState {
    idle("idle"),
    gazing("gazing"),
    listening("listening"),
    thinking("thinking"),
    happy("happy"),
    confused("confused"),
    sleepy("sleepy"),
    sleeping("sleeping"),
    waking("waking");

    private final String value;

    RobotState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static RobotState ofValue(String v) {
        for (RobotState e : values()) {
            if (e.value.equals(v)) return e;
        }
        return null;
    }
}
