package com.liming.rag.settings.infrastructure.cache;

import com.liming.rag.cache.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.TypedCache;
import com.liming.rag.settings.application.system.SavedSettingsCache;
import com.liming.rag.settings.application.system.SystemSettingsStore;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 用平台组件 rag-cache 缓存界面保存值，整份存在一个键下。 */
@Component
class RagSavedSettingsCache implements SavedSettingsCache {

    /** 缓存的值要是 record，列表包一层。 */
    record SavedSettings(List<SystemSettingsStore.Saved> items) {
    }

    static final CacheSpec<SavedSettings> SETTINGS = new CacheSpec<>("settings", SavedSettings.class);
    private static final String ALL = "all";

    private final TypedCache<SavedSettings> cache;

    RagSavedSettingsCache(Caches caches) {
        this.cache = caches.cache(SETTINGS);
    }

    @Override
    public List<SystemSettingsStore.Saved> get(Supplier<List<SystemSettingsStore.Saved>> loader) {
        var cached = cache.get(ALL, () -> new SavedSettings(List.copyOf(loader.get())));
        return cached == null ? loader.get() : cached.items();
    }

    @Override
    public void forget() {
        cache.evict(ALL);
    }
}
