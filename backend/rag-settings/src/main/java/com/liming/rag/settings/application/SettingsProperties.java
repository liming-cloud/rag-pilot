package com.liming.rag.settings.application;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 系统设置的配置（{@code rag.settings}）。
 *
 * @param masterKey 加密服务商密钥用的主密钥（环境变量 {@code RAG_MASTER_KEY}）：32 字节随机值的 Base64，
 *     可用 {@code openssl rand -base64 32} 生成；为空时不能在界面保存密钥（D-043）。不进日志。
 */
@ConfigurationProperties("rag.settings")
public record SettingsProperties(@DefaultValue("") String masterKey) {

    public SettingsProperties {
        masterKey = masterKey == null ? "" : masterKey.strip();
        if (!masterKey.isEmpty()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(masterKey);
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            if (decoded.length != 32) {
                throw invalid();
            }
        }
    }

    public boolean masterKeyConfigured() {
        return !masterKey.isEmpty();
    }

    /** 不输出主密钥。 */
    @Override
    public String toString() {
        return "SettingsProperties[masterKeyConfigured=" + masterKeyConfigured() + "]";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "RAG_MASTER_KEY 必须是 32 字节随机值的 Base64，可用 openssl rand -base64 32 生成");
    }
}
