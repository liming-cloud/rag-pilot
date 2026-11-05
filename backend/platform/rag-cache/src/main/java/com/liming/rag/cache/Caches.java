package com.liming.rag.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import tools.jackson.databind.json.JsonMapper;

/** 取一种缓存。同名的缓存共用同一份数据，各上下文的缓存名不要重复。 */
public interface Caches {

    <V> TypedCache<V> cache(CacheSpec<V> spec);

    /**
     * 存在内存里的缓存，机制和生产一样（JSON 往返、提交后清除），只用于单元测试：漏了清除、值存不成 JSON 时测试能发现。
     * 不过期。
     */
    static Caches inMemory() {
        return new JsonCaches(new MemoryCacheStore(), JsonMapper.builder().build(),
                new CacheProperties("test", Duration.ofSeconds(30)), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null);
    }
}
