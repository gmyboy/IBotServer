package com.pophie.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pophie.annotation.EncryptResponse;
import com.pophie.base.BaseResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 拦截带 {@link EncryptResponse} 注解的接口响应：
 * - 使用 AES-GCM (256-bit key / 96-bit IV / 128-bit auth tag) 对 BaseResponse.data 字段加密
 * - 输出格式：Base64(IV || ciphertext || authTag)
 * - 密钥由 {@link EncryptionProperties#getAesKey()} 提供（Base64 编码的 32 字节随机值）
 * - encrypted=true 写回响应体，客户端据此判断是否需要解密
 */
@RestControllerAdvice
public class ClientResponseEncryptAdvice implements ResponseBodyAdvice<BaseResponse> {

    private static final Logger log = LoggerFactory.getLogger(ClientResponseEncryptAdvice.class);
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN_BITS = 128;

    private final EncryptionProperties props;
    private final ObjectMapper objectMapper;
    private SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ClientResponseEncryptAdvice(EncryptionProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        if (props.getAesKey() == null || props.getAesKey().isEmpty()) {
            throw new IllegalStateException("app.crypto.aes-key 未配置");
        }
        byte[] keyBytes = Base64.getDecoder().decode(props.getAesKey());
        if (keyBytes.length != 32) {
            throw new IllegalStateException("app.crypto.aes-key 必须为 Base64 编码的 32 字节 (256-bit) AES 密钥，实际长度 " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (returnType.hasMethodAnnotation(EncryptResponse.class)) return true;
        Class<?> declaring = returnType.getDeclaringClass();
        return declaring.isAnnotationPresent(EncryptResponse.class);
    }

    @Override
    public BaseResponse beforeBodyWrite(BaseResponse body,
                                        MethodParameter returnType,
                                        MediaType selectedContentType,
                                        Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                        ServerHttpRequest request,
                                        ServerHttpResponse response) {
        if (body == null) return null;
        body.setEncrypted(false);
        if (body.getData() == null) return body;
        try {
            String json = objectMapper.writeValueAsString(body.getData());
            body.setData(encrypt(json));
            body.setEncrypted(true);
        } catch (Exception e) {
            log.error("[EncryptAdvice] 响应加密失败，降级为明文返回: {}", e.getMessage(), e);
            // 降级：保留 encrypted=false，业务流不中断
        }
        return body;
    }

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LEN];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
        byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[GCM_IV_LEN + cipherBytes.length];
        System.arraycopy(iv, 0, out, 0, GCM_IV_LEN);
        System.arraycopy(cipherBytes, 0, out, GCM_IV_LEN, cipherBytes.length);
        return Base64.getEncoder().encodeToString(out);
    }
}
