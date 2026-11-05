package com.liming.rag.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** 单元测试用的内存存储（{@link Caches#inMemory()}），不过期。 */
final class MemoryCacheStore implements CacheStore {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public @Nullable String get(String key) {
        return values.get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        values.put(key, value);
    }

    @Override
    public void delete(String key) {
        values.remove(key);
    }

    @Override
    public void deleteByPrefix(String prefix) {
        values.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
