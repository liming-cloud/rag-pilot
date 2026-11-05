package com.liming.rag.archfixture.application;

import com.liming.rag.cache.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.TypedCache;

/** 违规样例（D-054）：应用层直接用缓存组件，应在 application 声明业务化的缓存端口，由 infrastructure 用 rag-cache 实现。 */
public class ApplicationUsesCaches {

    record Snapshot(long id) {
    }

    private final TypedCache<Snapshot> cache;

    ApplicationUsesCaches(Caches caches) {
        this.cache = caches.cache(new CacheSpec<>("fixture", Snapshot.class));
    }

    Snapshot find(long id) {
        return cache.get(String.valueOf(id), () -> new Snapshot(id));
    }
}
