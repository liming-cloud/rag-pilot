package com.liming.rag.identity.application;

/** 密码摘要端口（#136）：只存摘要，不存明文；实现在 {@code infrastructure.security}（BCrypt）。 */
public interface PasswordHasher {

    /** 带随机盐的摘要，同一个密码每次结果不同。 */
    String hash(String password);

    /** 密码与摘要是否匹配；密码为 null 时按空串比对。 */
    boolean matches(String password, String hash);
}
