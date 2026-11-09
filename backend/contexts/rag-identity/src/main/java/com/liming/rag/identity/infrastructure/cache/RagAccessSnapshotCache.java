package com.liming.rag.identity.infrastructure.cache;

import com.liming.rag.cache.typed.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.typed.TypedCache;
import com.liming.rag.identity.application.role.AccessSnapshot;
import com.liming.rag.identity.application.role.AccessSnapshotCache;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 用平台组件 rag-cache 缓存权限快照，按账号一份。 */
@Component
class RagAccessSnapshotCache implements AccessSnapshotCache {

    static final CacheSpec<AccessSnapshot> ACCESS = new CacheSpec<>("access", AccessSnapshot.class);

    private final TypedCache<AccessSnapshot> cache;

    RagAccessSnapshotCache(Caches caches) {
        this.cache = caches.cache(ACCESS);
    }

    @Override
    public @Nullable AccessSnapshot get(long accountId, Supplier<@Nullable AccessSnapshot> loader) {
        return cache.get(String.valueOf(accountId), loader);
    }

    @Override
    public void forget(long accountId) {
        cache.evict(String.valueOf(accountId));
    }

    @Override
    public void forgetAll() {
        cache.clear();
    }
}
