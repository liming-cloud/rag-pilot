package com.liming.rag.cache.typed;

import org.springframework.modulith.NamedInterface;
import java.util.regex.Pattern;

/**
 * 一种缓存：名字（小写字母、数字和连字符，作为键的一段）和值的类型（record，存进 Redis 时是 JSON）。
 * 用哪几层、过期时间、容量由配置 {@code rag.cache} 决定，不在这里写（D-054）。
 */
@NamedInterface("typed")
public record CacheSpec<V>(String name, Class<V> type) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    public CacheSpec {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("缓存名只能用小写字母、数字和连字符，以字母开头：" + name);
        }
    }
}
