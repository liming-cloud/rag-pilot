package com.liming.rag.cache;

import java.time.Duration;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;

/**
 * 缓存经 Redisson 存进 Redis，值按字符串存（JSON 由 {@link JsonCaches} 负责）。按前缀删除用 SCAN 分批找键、
 * UNLINK 删除，不用 KEYS，不阻塞 Redis。
 */
final class RedisCacheStore implements CacheStore {

    private static final int BATCH = 500;

    private final RedissonClient redis;

    RedisCacheStore(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public @Nullable String get(String key) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).get();
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        redis.<String>getBucket(key, StringCodec.INSTANCE).set(value, ttl);
    }

    @Override
    public void delete(String key) {
        redis.getKeys().unlink(key);
    }

    @Override
    public void deleteByPrefix(String prefix) {
        var options = KeysScanOptions.defaults().pattern(escape(prefix) + "*").chunkSize(BATCH);
        var batch = new ArrayList<String>(BATCH);
        for (var key : redis.getKeys().getKeys(options)) {
            batch.add(key);
            if (batch.size() == BATCH) {
                redis.getKeys().unlink(batch.toArray(String[]::new));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            redis.getKeys().unlink(batch.toArray(String[]::new));
        }
    }

    /** SCAN 的匹配模式里 {@code * ? [ ] \} 是通配符，前缀里的要转义。 */
    private static String escape(String prefix) {
        return prefix.replaceAll("([*?\\[\\]\\\\])", "\\\\$1");
    }
}
