package com.liming.rag.data.application;

import com.liming.rag.shared.TenantId;
import java.util.List;

/** 站点主数据端口（D-059）：由客户系统的只读工具提供，不存库。取不到时抛 {@link DataException}（UNAVAILABLE）。 */
public interface UnitDirectory {

    List<ScopeUnit> units(TenantId tenantId);
}
