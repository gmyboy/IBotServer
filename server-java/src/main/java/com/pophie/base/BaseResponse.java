package com.pophie.base;

import com.pophie.utils.NetConstant;

import java.io.Serializable;

public class BaseResponse implements Serializable {

    private int code;
    private String msg;
    private boolean successful;
    private Object data;

    /** 是否已加密（由 ClientResponseEncryptAdvice 设置）。客户端据此判断是否需要解密 data。 */
    private boolean encrypted = false;

    public BaseResponse() {
        this(NetConstant.RESP_SUCCESS);
    }

    public BaseResponse(NetConstant netConstant) {
        this.code = netConstant.getCode();
        this.msg = netConstant.getMsg();
        this.successful = (this.code == NetConstant.RESP_SUCCESS.getCode());
    }

    public BaseResponse(int code, String msg) {
        this.code = code;
        this.msg = msg;
        this.successful = (code == NetConstant.RESP_SUCCESS.getCode());
    }

    public BaseResponse(Object data) {
        this(NetConstant.RESP_SUCCESS);
        this.data = data;
    }

    public void setResponse(NetConstant netConstant) {
        this.code = netConstant.getCode();
        this.msg = netConstant.getMsg();
        this.successful = (this.code == NetConstant.RESP_SUCCESS.getCode());
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }

    public boolean getSuccessful() { return successful; }
    public void setSuccessful(boolean successful) { this.successful = successful; }

    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }

    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

    public boolean isCheckSuccess() {
        return code == NetConstant.RESP_SUCCESS.getCode();
    }
}
