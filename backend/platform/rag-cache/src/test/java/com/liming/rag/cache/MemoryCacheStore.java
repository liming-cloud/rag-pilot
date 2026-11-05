package com.liming.rag.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** 测试二级缓存机制用的内存存储，不过期；比较代数再写入等操作加锁，和 Redis 里一样是原子的。 */
class MemoryCacheStore implements CacheStore {

    final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public @Nullable String get(String key) {
        return values.get(key);
    }

    @Override
    public String generation(String generationKey) {
        return values.getOrDefault(generationKey, "0");
    }

    @Override
    public synchronized boolean setIfGeneration(String key, String value, Duration ttl, String generationKey,
            String expected) {
        if (!generation(generationKey).equals(expected)) {
            return false;
        }
        values.put(key, value);
        return true;
    }

    @Override
    public synchronized void bumpAndDelete(String generationKey, String key) {
        bump(generationKey);
        values.remove(key);
    }

    @Override
    public synchronized void bumpAndDeleteByPrefix(String generationKey, String prefix) {
        bump(generationKey);
        values.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void bump(String generationKey) {
        values.put(generationKey, String.valueOf(Long.parseLong(generation(generationKey)) + 1));
    }
}
