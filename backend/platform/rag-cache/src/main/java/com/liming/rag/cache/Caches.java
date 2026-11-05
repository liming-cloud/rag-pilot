package com.liming.rag.cache;

import java.time.Clock;
import tools.jackson.databind.json.JsonMapper;

/** 取一种缓存。同名的缓存共用同一份数据，各上下文的缓存名不要重复。 */
public interface Caches {

    <V> TypedCache<V> cache(CacheSpec<V> spec);

    /**
     * 只有一级缓存的实现，和没有 Redis 时的生产实现相同（提交后清除），用于单元测试：漏了清除时测试能发现。
     */
    static Caches inMemory() {
        return new JsonCaches(null, JsonMapper.builder().build(), CacheProperties.localOnly(), Clock.systemUTC(), null);
    }
}
