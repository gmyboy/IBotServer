package com.pophie.voice;

/** 输出门控模式。 */
public enum GateMode {
    /** 全部语音帧都输出，附带说话人可信度，由上层自行取舍。 */
    REPORT,
    /** 仅输出判定为主人的语音帧（其余拦截）。 */
    OWNER_ONLY,
}
