package com.liming.rag.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置。Redis 的地址、密码和超时用 Spring Boot 的 {@code spring.data.redis.*}。
 *
 * @param prefix 缓存键的前缀，和同一个 Redis 里的会话（Sa-Token）区分开
 * @param retryAfter Redis 出错后多久内不再访问它、直接读库，免得每次都等到超时
 */
@ConfigurationProperties("rag.cache")
record CacheProperties(String prefix, Duration retryAfter) {

    CacheProperties {
        if (prefix == null || prefix.isBlank() || prefix.contains("*")) {
            throw new IllegalArgumentException("rag.cache.prefix 不能为空，也不能含 *");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("rag.cache.retry-after 必须大于 0");
        }
    }
}
