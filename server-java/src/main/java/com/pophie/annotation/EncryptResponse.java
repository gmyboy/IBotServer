package com.pophie.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Controller 类或方法，表示该接口响应 data 字段需要加密。
 * - 类级别：该类所有方法生效
 * - 方法级别：仅该方法生效
 *
 * 加密由 {@link com.pophie.configuration.ClientResponseEncryptAdvice} 通过 AES-GCM 完成。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EncryptResponse {
}
