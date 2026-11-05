package com.liming.rag.cache;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置（D-053、D-054）。上下文只声明缓存名和值的类型，用哪几层、过期时间、容量都在这里。
 *
 * @param prefix Redis 里键的前缀，和同一个 Redis 里的会话区分开
 * @param retryAfter Redis 出错后多久内不再访问它、直接读库，免得每次都等到超时
 * @param redis 是否用 Redis 及连接参数；不用时缓存只有一级，会话存在进程内
 * @param defaults 每种缓存默认的分层配置，各项都要填
 * @param caches 按缓存名覆盖默认值，只写要改的项
 */
@ConfigurationProperties("rag.cache")
record CacheProperties(String prefix, Duration retryAfter, @Nullable Redis redis, Tiers defaults,
        @Nullable Map<String, Tiers> caches) {

    private static final Redis NO_REDIS =
            new Redis(false, "localhost", 6379, null, Duration.ofMillis(500), Duration.ofSeconds(2));

    CacheProperties {
        if (prefix == null || prefix.isBlank() || prefix.contains("*")) {
            throw new IllegalArgumentException("rag.cache.prefix 不能为空，也不能含 *");
        }
        positive("rag.cache.retry-after", retryAfter);
        redis = redis == null ? NO_REDIS : redis;
        if (defaults == null || defaults.local() == null || defaults.remote() == null
                || defaults.local().enabled() == null || defaults.local().maxSize() == null
                || defaults.remote().enabled() == null) {
            throw new IllegalArgumentException(
                    "rag.cache.defaults 的 local（enabled、max-size、ttl）和 remote（enabled、ttl）都要填");
        }
        caches = caches == null ? Map.of() : Map.copyOf(caches);
        resolve(defaults, null);
        for (var entry : caches.entrySet()) {
            var policy = resolve(defaults, entry.getValue());
            if (!policy.local() && !policy.remote()) {
                throw new IllegalArgumentException("缓存 " + entry.getKey() + " 的一级、二级都关了");
            }
        }
    }

    /** 单元测试用：不用 Redis，一级缓存容量 10000、过期 5 分钟。 */
    static CacheProperties localOnly() {
        return new CacheProperties("rag:cache", Duration.ofSeconds(30), null,
                new Tiers(new Local(true, 10_000L, Duration.ofMinutes(5)), new Remote(true, Duration.ofHours(1))),
                null);
    }

    boolean redisEnabled() {
        return redis != null && redis.enabled();
    }

    /** 一种缓存最终用哪几层；没有 Redis 时二级不生效，只开了二级的缓存退回一级。 */
    Policy policy(String cache) {
        var policy = resolve(defaults, caches == null ? null : caches.get(cache));
        if (redisEnabled()) {
            return policy;
        }
        return new Policy(true, policy.maxSize(), policy.localTtl(), false, policy.remoteTtl());
    }

    private static Policy resolve(Tiers defaults, @Nullable Tiers override) {
        var local = override == null || override.local() == null ? new Local(null, null, null) : override.local();
        var remote = override == null || override.remote() == null ? new Remote(null, null) : override.remote();
        var policy = new Policy(
                or(local.enabled(), defaults.local().enabled()),
                or(local.maxSize(), defaults.local().maxSize()),
                or(local.ttl(), defaults.local().ttl()),
                or(remote.enabled(), defaults.remote().enabled()),
                or(remote.ttl(), defaults.remote().ttl()));
        if (policy.maxSize() <= 0) {
            throw new IllegalArgumentException("rag.cache 的 local.max-size 必须大于 0");
        }
        positive("rag.cache 的 local.ttl", policy.localTtl());
        positive("rag.cache 的 remote.ttl", policy.remoteTtl());
        return policy;
    }

    private static <T> T or(@Nullable T value, @Nullable T fallback) {
        return value != null ? value : fallback;
    }

    private static void positive(String name, @Nullable Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }

    /**
     * Redis 连接。
     *
     * @param enabled 打开后缓存有二级、会话存进 Redis（应用重启后不用重新登录）；Redis 不在时应用起不来
     * @param timeout 命令超时，超时按缓存出错处理
     */
    record Redis(boolean enabled, String host, int port, @Nullable String password, Duration timeout,
            Duration connectTimeout) {

        Redis {
            if (enabled) {
                if (host == null || host.isBlank() || port <= 0) {
                    throw new IllegalArgumentException("rag.cache.redis 打开时 host、port 必须填");
                }
                positive("rag.cache.redis.timeout", timeout);
                positive("rag.cache.redis.connect-timeout", connectTimeout);
            }
        }
    }

    /** 一种缓存的两层配置；覆盖时没写的项沿用默认值。 */
    record Tiers(@Nullable Local local, @Nullable Remote remote) {
    }

    /** 一级缓存：进程内（Caffeine），超过容量时淘汰，写入后过了过期时间失效。 */
    record Local(@Nullable Boolean enabled, @Nullable Long maxSize, @Nullable Duration ttl) {
    }

    /** 二级缓存：Redis，只在 {@code rag.cache.redis.enabled} 打开时生效。 */
    record Remote(@Nullable Boolean enabled, @Nullable Duration ttl) {
    }

    record Policy(boolean local, long maxSize, Duration localTtl, boolean remote, Duration remoteTtl) {
    }
}
