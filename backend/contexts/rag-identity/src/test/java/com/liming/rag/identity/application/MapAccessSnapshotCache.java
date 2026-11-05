package com.liming.rag.identity.application;

import com.liming.rag.identity.application.role.AccessSnapshot;
import com.liming.rag.identity.application.role.AccessSnapshotCache;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** 单元测试用的权限快照缓存：存在 Map 里，清除立即生效。 */
public final class MapAccessSnapshotCache implements AccessSnapshotCache {

    private final Map<Long, AccessSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public @Nullable AccessSnapshot get(long accountId, Supplier<@Nullable AccessSnapshot> loader) {
        var cached = snapshots.get(accountId);
        if (cached != null) {
            return cached;
        }
        var loaded = loader.get();
        if (loaded != null) {
            snapshots.put(accountId, loaded);
        }
        return loaded;
    }

    @Override
    public void forget(long accountId) {
        snapshots.remove(accountId);
    }

    @Override
    public void forgetAll() {
        snapshots.clear();
    }
}
