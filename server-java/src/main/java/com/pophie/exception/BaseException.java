package com.pophie.exception;

import com.pophie.base.BaseResponse;
import com.pophie.utils.NetConstant;

public class BaseException extends RuntimeException {

    private int errorCode = NetConstant.RESP_FAIL.getCode();
    private String errorMsg;

    public BaseException() {}

    public BaseException(String message) {
        super(message);
        this.errorMsg = message;
    }

    public BaseException(String message, int errorCode) {
        super(message);
        this.errorMsg = message;
        this.errorCode = errorCode;
    }

    public BaseException(String message, Throwable cause) {
        super(message, cause);
        this.errorMsg = message;
    }

    public BaseResponse getErrorResponse() {
        return new BaseResponse(errorCode, errorMsg);
    }

    public int getErrorCode() { return errorCode; }
    public String getErrorMsg() { return errorMsg; }
}
