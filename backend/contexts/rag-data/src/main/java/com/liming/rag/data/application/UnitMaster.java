package com.liming.rag.data.application;

import com.liming.rag.shared.TenantId;
import java.util.List;
import org.springframework.stereotype.Service;

/** 站点、区域主数据（D-059）：从客户系统读取，经缓存；授权页选择和区域展开都用它。 */
@Service
public class UnitMaster {

    private final UnitDirectory directory;
    private final UnitCache cache;

    public UnitMaster(UnitDirectory directory, UnitCache cache) {
        this.directory = directory;
        this.cache = cache;
    }

    /** 取不到时抛 {@link DataException}（UNAVAILABLE）。 */
    public List<ScopeUnit> units(TenantId tenantId) {
        return cache.get(tenantId, () -> directory.units(tenantId));
    }
}
