package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;

/**
 * 登录会话端口（#136）：会话由 Sa-Token 管理（试点存内存，D-005），实现在 {@code infrastructure.security}。
 * 应用层只经这里登录、退出、踢下线、取当前登录人，不直接调用 Sa-Token。
 */
public interface Sessions {

    /** 为这个账号建立会话并记下租户，返回令牌。 */
    String login(long accountId, TenantId tenantId);

    /** 退出当前请求的会话。 */
    void logoutCurrent();

    /** 踢掉这个账号所有已登录的会话，令牌立即失效（停用、重置密码时）。 */
    void logoutAll(long accountId);

    /** 当前请求登录的账号；没登录时由实现抛出未登录异常（接口返回 401）。 */
    long currentAccountId();

    /** 当前请求登录的租户，登录时记在会话里。 */
    TenantId currentTenantId();
}
