package com.liming.rag.identity.api;

import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;

/**
 * 当前请求的登录用户。只在已通过 {@code @SaCheckLogin} 或 {@code @SaCheckRole} 的接口里调用。
 */
public interface CurrentUser {

    UserId userId();

    TenantId tenantId();
}
