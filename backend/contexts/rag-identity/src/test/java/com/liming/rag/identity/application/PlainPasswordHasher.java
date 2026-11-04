package com.liming.rag.identity.application;

import com.liming.rag.identity.application.auth.PasswordHasher;

/** 单元测试用的摘要：可逆、很快，只用来确认存的不是明文、比对走的是摘要。BCrypt 本身在 infrastructure 的测试里验证。 */
public final class PlainPasswordHasher implements PasswordHasher {

    public static final String PREFIX = "hashed:";

    @Override
    public String hash(String password) {
        return PREFIX + password;
    }

    @Override
    public boolean matches(String password, String hash) {
        return hash.equals(PREFIX + (password == null ? "" : password));
    }
}
