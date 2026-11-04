package com.liming.rag.settings.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 服务商密钥的加解密（D-043）：JDK 自带的 AES-256-GCM，主密钥来自 {@code RAG_MASTER_KEY}。存库的是 12 字节随机 IV
 * 加密文（含认证标签），被改动或主密钥不对时解密失败，不会解出错误的明文。
 */
@Component
public class KeyCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public KeyCipher(SettingsProperties properties) {
        this.key = properties.masterKeyConfigured()
                ? new SecretKeySpec(Base64.getDecoder().decode(properties.masterKey()), "AES")
                : null;
    }

    public boolean available() {
        return key != null;
    }

    public byte[] encrypt(String plain) {
        requireKey();
        var iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            var out = Arrays.copyOf(iv, IV_BYTES + encrypted.length);
            System.arraycopy(encrypted, 0, out, IV_BYTES, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密服务商密钥失败", e);
        }
    }

    /** @throws SettingsException 主密钥没设、变了，或密文被改动 */
    public String decrypt(byte[] stored) {
        requireKey();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            return new String(cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SettingsException(SettingsException.Reason.KEY_UNREADABLE,
                    "服务商密钥解不开：主密钥 RAG_MASTER_KEY 变了或没设，请在模型管理里重新录入密钥");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new SettingsException(SettingsException.Reason.MASTER_KEY_MISSING,
                    "没有设置主密钥 RAG_MASTER_KEY，不能保存或读取服务商密钥");
        }
    }
}
