package com.pophie.exception;

/**
 * 对应 FastAPI 的 HTTPException(status_code, detail)。
 * 业务接口直接返回原始 JSON，错误体复刻 FastAPI 的 {"detail": "..."} + 对应 HTTP 状态码，
 * 保证前端 index.html/admin.html 与 Android 客户端零改动。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
