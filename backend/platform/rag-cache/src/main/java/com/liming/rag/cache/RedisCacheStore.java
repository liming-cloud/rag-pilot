package com.liming.rag.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;

/**
 * 缓存经 Redisson 存进 Redis，值按字符串存（JSON 由 {@link JsonCaches} 负责）。比较代数再写入、代数加一再删除用 Lua 脚本，
 * 在 Redis 里原子执行。按前缀删除用 SCAN 分批找键、UNLINK 删除，不用 KEYS，不阻塞 Redis。
 */
final class RedisCacheStore implements CacheStore {

    private static final int BATCH = 500;

    private static final String SET_IF_GENERATION = """
            local current = redis.call('GET', KEYS[1]) or '0'
            if current ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1""";

    private static final String BUMP_AND_DELETE = """
            redis.call('INCR', KEYS[1])
            redis.call('UNLINK', KEYS[2])
            return 1""";

    private final RedissonClient redis;

    RedisCacheStore(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public @Nullable String get(String key) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).get();
    }

    @Override
    public String generation(String generationKey) {
        var value = redis.<String>getBucket(generationKey, StringCodec.INSTANCE).get();
        return value == null ? "0" : value;
    }

    @Override
    public boolean setIfGeneration(String key, String value, Duration ttl, String generationKey, String expected) {
        Long written = script().eval(RScript.Mode.READ_WRITE, SET_IF_GENERATION, RScript.ReturnType.LONG,
                List.of(generationKey, key), expected, value, String.valueOf(ttl.toMillis()));
        return written != null && written == 1L;
    }

    @Override
    public void bumpAndDelete(String generationKey, String key) {
        script().eval(RScript.Mode.READ_WRITE, BUMP_AND_DELETE, RScript.ReturnType.LONG,
                List.of(generationKey, key));
    }

    @Override
    public void bumpAndDeleteByPrefix(String generationKey, String prefix) {
        // 先加一：之后才写回的旧值都会被拒绝，再慢慢删已有的键。
        redis.getAtomicLong(generationKey).incrementAndGet();
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

    private RScript script() {
        return redis.getScript(StringCodec.INSTANCE);
    }

    /** SCAN 的匹配模式里 {@code * ? [ ] \} 是通配符，前缀里的要转义。 */
    private static String escape(String prefix) {
        return prefix.replaceAll("([*?\\[\\]\\\\])", "\\\\$1");
    }
}
