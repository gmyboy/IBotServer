package com.pophie.exception;

import com.pophie.utils.NetConstant;

public class HttpRequestException extends RuntimeException {

    private NetConstant constant = NetConstant.RESP_FAIL;
    private String message;
    private int code = NetConstant.RESP_FAIL.getCode();

    public HttpRequestException() { super(); }

    public HttpRequestException(NetConstant constant) {
        this.constant = constant;
        this.message = constant.getMsg();
        this.code = constant.getCode();
    }

    public HttpRequestException(String message) {
        super(message);
        this.message = message;
    }

    public HttpRequestException(String message, int code) {
        super(message);
        this.message = message;
        this.code = code;
    }

    public HttpRequestException(String message, Throwable cause) {
        super(message, cause);
        this.message = message;
    }

    @Override
    public String getMessage() { return message; }

    public void setMessage(String message) { this.message = message; }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public NetConstant getConstant() { return constant; }

    public void setConstant(NetConstant constant) {
        this.constant = constant;
        this.message = constant.getMsg();
        this.code = constant.getCode();
    }
}
