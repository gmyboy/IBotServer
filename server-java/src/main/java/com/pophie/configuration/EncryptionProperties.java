package com.pophie.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.crypto")
@Data
public class EncryptionProperties {

    /**
     * Base64-encoded 32-byte (256-bit) AES key.
     * 生产部署生成：openssl rand -base64 32
     */
    private String aesKey;
}
