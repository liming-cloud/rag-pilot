package com.liming.rag.settings.application.model;

import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.settings.application.SettingsProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 服务商密钥的加解密与主密钥校验（D-043）。 */
class KeyCipherTest {

    static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("x".repeat(32).getBytes());

    @Test
    @DisplayName("加密后能解回原文，密文里没有明文，同一明文每次密文都不同")
    void roundTrip() {
        var cipher = new KeyCipher(new SettingsProperties(MASTER_KEY));
        var first = cipher.encrypt("sk-secret-value");
        var second = cipher.encrypt("sk-secret-value");

        assertThat(cipher.decrypt(first)).isEqualTo("sk-secret-value");
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("sk-secret");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("密文被改动或主密钥变了时解不开，提示重新录入")
    void rejectsTamperingAndWrongKey() {
        var stored = new KeyCipher(new SettingsProperties(MASTER_KEY)).encrypt("sk-secret-value");
        var tampered = stored.clone();
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> new KeyCipher(new SettingsProperties(MASTER_KEY)).decrypt(tampered))
                .isInstanceOf(SettingsException.class).hasMessageContaining("重新录入密钥");
        assertThatThrownBy(() -> new KeyCipher(new SettingsProperties(OTHER_KEY)).decrypt(stored))
                .isInstanceOf(SettingsException.class).hasMessageContaining("主密钥");
    }

    @Test
    @DisplayName("没设主密钥时不能加密；主密钥格式不对时启动失败；toString 不输出主密钥")
    void masterKeyRules() {
        var none = new KeyCipher(new SettingsProperties(""));
        assertThat(none.available()).isFalse();
        assertThatThrownBy(() -> none.encrypt("k")).hasMessageContaining("RAG_MASTER_KEY");

        assertThatThrownBy(() -> new SettingsProperties("not-base64!")).hasMessageContaining("32 字节");
        assertThatThrownBy(() -> new SettingsProperties(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 字节");
        assertThat(new SettingsProperties(MASTER_KEY).toString()).doesNotContain(MASTER_KEY);
    }
}
