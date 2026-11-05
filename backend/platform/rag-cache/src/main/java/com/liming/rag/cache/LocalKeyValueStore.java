package com.liming.rag.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 没有 Redis 时的键值存储：进程内（Caffeine 按条目过期），不按容量淘汰；应用重启后丢失。 */
final class LocalKeyValueStore implements KeyValueStore {

    /** Caffeine 能表示的最长过期时间约 150 年，超过 100 年的按不过期处理。 */
    private static final Duration FOREVER = Duration.ofDays(365L * 140);
    private static final Duration PERSISTENT_THRESHOLD = Duration.ofDays(365L * 100);

    private final Cache<String, String> values;
    private final VarExpiration<String, String> expiration;

    LocalKeyValueStore(Clock clock) {
        this.values = Caffeine.newBuilder()
                .ticker(() -> clock.millis() * 1_000_000L)
                .expireAfter(new Expiry<String, String>() {
                    @Override
                    public long expireAfterCreate(String key, String value, long currentTime) {
                        return FOREVER.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
        this.expiration = values.policy().expireVariably().orElseThrow();
    }

    @Override
    public @Nullable String get(String key) {
        return values.getIfPresent(key);
    }

    @Override
    public void set(String key, String value, @Nullable Duration ttl) {
        expiration.put(key, value, ttl == null ? FOREVER : ttl);
    }

    @Override
    public boolean update(String key, String value) {
        return values.asMap().computeIfPresent(key, (k, old) -> value) != null;
    }

    @Override
    public void delete(String key) {
        values.invalidate(key);
    }

    @Override
    public long remaining(String key) {
        if (values.getIfPresent(key) == null) {
            return MISSING;
        }
        var left = expiration.getExpiresAfter(key).orElse(null);
        if (left == null) {
            return MISSING;
        }
        return left.compareTo(PERSISTENT_THRESHOLD) > 0 ? PERSISTENT : left.toMillis();
    }

    @Override
    public void expire(String key, @Nullable Duration ttl) {
        if (values.getIfPresent(key) != null) {
            expiration.setExpiresAfter(key, ttl == null ? FOREVER : ttl);
        }
    }

    @Override
    public List<String> keys(String prefix) {
        return values.asMap().keySet().stream().filter(key -> key.startsWith(prefix)).toList();
    }
}
