package com.liming.rag.data.infrastructure.cache;

import com.liming.rag.cache.typed.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.typed.TypedCache;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.UnitCache;
import com.liming.rag.shared.TenantId;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 用平台组件 rag-cache 缓存站点主数据，每个租户一个键。取不到数据时（loader 抛异常）不缓存。 */
@Component
class RagUnitCache implements UnitCache {

    /** 缓存的值要是 record，列表包一层。 */
    record Units(List<ScopeUnit> items) {
    }

    static final CacheSpec<Units> UNITS = new CacheSpec<>("data-units", Units.class);

    private final TypedCache<Units> cache;

    RagUnitCache(Caches caches) {
        this.cache = caches.cache(UNITS);
    }

    @Override
    public List<ScopeUnit> get(TenantId tenantId, Supplier<List<ScopeUnit>> loader) {
        var cached = cache.get(String.valueOf(tenantId.value()), () -> new Units(List.copyOf(loader.get())));
        return cached == null ? loader.get() : cached.items();
    }
}
