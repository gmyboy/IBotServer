package com.pophie.utils;

public enum NetConstant {

    RESP_SUCCESS(1000, "成功"),
    RESP_FAIL(1001, "请求失败"),
    RESP_PARAMS_ERROR(1002, "参数错误"),
    RESP_TOKEN_INVALID(1004, "登录已过期，请重新登录"),
    RESP_NO_PERMISSION(1005, "无权限访问");

    private final int code;
    private final String msg;

    NetConstant(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() { return code; }
    public String getMsg() { return msg; }
}
