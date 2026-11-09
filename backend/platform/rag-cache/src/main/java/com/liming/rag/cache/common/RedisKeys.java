package com.liming.rag.cache.common;

/** 两种能力共用的 Redis 键工具。 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** SCAN 的匹配模式里 {@code * ? [ ] \} 是通配符，前缀里的要转义。 */
    public static String escape(String prefix) {
        return prefix.replaceAll("([*?\\[\\]\\\\])", "\\\\$1");
    }
}
