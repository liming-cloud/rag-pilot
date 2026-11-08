package com.liming.rag.data.application;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;

/** 授权的存取端口。 */
public interface DataGrantStore {

    /** 授权给一个用户或角色的内容，没有授权时为空。 */
    Grants find(TenantId tenantId, GranteeType type, long granteeId);

    /** 用户本人的授权和所属角色的授权的并集（D-059）。 */
    Grants findEffective(TenantId tenantId, UserId userId, Collection<Long> roleIds);

    /** 用 {@code grants} 整体替换这个用户或角色的授权。 */
    void replace(TenantId tenantId, GranteeType type, long granteeId, Grants grants);

    /** 角色被删除时收回授权给它的内容。 */
    void removeRole(TenantId tenantId, long roleId);
}
