package com.pophie.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：复刻 FastAPI 的错误契约。
 * - 业务异常一律返回 {"detail": "..."} + 对应 HTTP 状态码（不是脚手架的 {code,msg}），
 *   以保证前端/Android 零改动。
 * - sa-token 未登录/无权限 → 401/403 + {"detail": ...}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private ResponseEntity<Map<String, Object>> detail(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("detail", msg == null ? "" : msg));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> onApi(ApiException e) {
        return detail(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(HttpRequestException.class)
    public ResponseEntity<Map<String, Object>> onBiz(HttpRequestException e) {
        return detail(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<Map<String, Object>> onBase(BaseException e) {
        return detail(HttpStatus.BAD_REQUEST.value(), e.getErrorMsg());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, Object>> onNotLogin(NotLoginException e) {
        return detail(HttpStatus.UNAUTHORIZED.value(), "invalid admin token");
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<Map<String, Object>> onNoPermission(Exception e) {
        return detail(HttpStatus.FORBIDDEN.value(), "no permission");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onInvalidArg(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        // FastAPI/Pydantic 校验失败用 422
        return detail(HttpStatus.UNPROCESSABLE_ENTITY.value(), msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnknown(Exception e) {
        logger.error("unhandled exception", e);
        return detail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal error");
    }
}
