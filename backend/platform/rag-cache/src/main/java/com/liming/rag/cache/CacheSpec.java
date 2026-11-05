package com.liming.rag.cache;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 一种缓存：名字（小写字母、数字和连字符，作为键的一段）、值的类型（record，存成 JSON）和过期时间。
 * 过期时间是清除之外的兜底，值以清除为准。
 */
public record CacheSpec<V>(String name, Class<V> type, Duration ttl) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    public CacheSpec {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("缓存名只能用小写字母、数字和连字符，以字母开头：" + name);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("缓存 " + name + " 的过期时间必须大于 0");
        }
    }
}
