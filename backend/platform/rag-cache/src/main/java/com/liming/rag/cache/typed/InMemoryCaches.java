package com.liming.rag.cache.typed;

import com.liming.rag.cache.Caches;
import com.liming.rag.cache.common.CacheProperties;
import java.time.Clock;
import tools.jackson.databind.json.JsonMapper;

/** {@link Caches#inMemory()} 的实现入口：根包接口不能直接访问包内私有的 {@code JsonCaches}。 */
public final class InMemoryCaches {

    private InMemoryCaches() {
    }

    public static Caches create() {
        return new JsonCaches(null, JsonMapper.builder().build(), CacheProperties.localOnly(), Clock.systemUTC(), null);
    }
}
