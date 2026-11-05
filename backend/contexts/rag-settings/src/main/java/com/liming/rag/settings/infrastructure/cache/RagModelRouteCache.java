package com.liming.rag.settings.infrastructure.cache;

import com.liming.rag.settings.application.model.ModelPurpose;
import com.liming.rag.cache.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.TypedCache;
import com.liming.rag.settings.application.model.ModelRouteCache;
import com.liming.rag.settings.application.model.SavedRoute;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 用平台组件 rag-cache 缓存各用途的路由，按用途一份；服务商的密钥只存密文。 */
@Component
class RagModelRouteCache implements ModelRouteCache {

    static final CacheSpec<SavedRoute> ROUTES = new CacheSpec<>("model-routes", SavedRoute.class);

    private final TypedCache<SavedRoute> cache;

    RagModelRouteCache(Caches caches) {
        this.cache = caches.cache(ROUTES);
    }

    @Override
    public SavedRoute get(ModelPurpose usage, Supplier<SavedRoute> loader) {
        var cached = cache.get(usage.name(), loader);
        return cached == null ? loader.get() : cached;
    }

    @Override
    public void forgetAll() {
        cache.clear();
    }
}
