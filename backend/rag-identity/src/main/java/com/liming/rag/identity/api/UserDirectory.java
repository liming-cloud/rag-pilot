package com.liming.rag.identity.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;

/** 账号查询，供其他模块在授权前确认账号存在。 */
public interface UserDirectory {

    /** 账号存在、属于该租户且未停用时返回 true。 */
    boolean exists(TenantId tenantId, UserId userId);
}
