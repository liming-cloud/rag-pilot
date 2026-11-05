package com.liming.rag.cache;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;

/** 键值存储放在 Redis 里，键是 {@code <前缀>:<名字>:<键>}，值按字符串存。 */
final class RedisKeyValueStore implements KeyValueStore {

    private static final String UPDATE_KEEP_TTL = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
            return 1""";

    private final RedissonClient redis;
    private final String namespace;

    RedisKeyValueStore(RedissonClient redis, String namespace) {
        this.redis = redis;
        this.namespace = namespace;
    }

    @Override
    public @Nullable String get(String key) {
        return bucket(key).get();
    }

    @Override
    public void set(String key, String value, @Nullable Duration ttl) {
        if (ttl == null) {
            bucket(key).set(value);
        } else {
            bucket(key).set(value, ttl);
        }
    }

    @Override
    public boolean update(String key, String value) {
        Long updated = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, UPDATE_KEEP_TTL,
                RScript.ReturnType.LONG, List.of(namespace + key), value);
        return updated != null && updated == 1L;
    }

    @Override
    public void delete(String key) {
        bucket(key).delete();
    }

    @Override
    public long remaining(String key) {
        return bucket(key).remainTimeToLive();
    }

    @Override
    public void expire(String key, @Nullable Duration ttl) {
        if (ttl == null) {
            bucket(key).clearExpire();
        } else {
            bucket(key).expire(ttl);
        }
    }

    @Override
    public List<String> keys(String prefix) {
        var options = KeysScanOptions.defaults().pattern(RedisCacheStore.escape(namespace + prefix) + "*").chunkSize(500);
        return redis.getKeys().getKeysStream(options).map(key -> key.substring(namespace.length())).toList();
    }

    private RBucket<String> bucket(String key) {
        return redis.getBucket(namespace + key, StringCodec.INSTANCE);
    }
}
