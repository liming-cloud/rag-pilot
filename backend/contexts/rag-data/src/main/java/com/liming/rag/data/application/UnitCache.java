package com.liming.rag.data.application;

import com.liming.rag.shared.TenantId;
import java.util.List;
import java.util.function.Supplier;

/** 站点主数据缓存端口：过期时间由缓存配置决定；客户系统的数据变了，最多滞后一个过期时间。 */
public interface UnitCache {

    List<ScopeUnit> get(TenantId tenantId, Supplier<List<ScopeUnit>> loader);
}
