package com.liming.rag.identity.infrastructure.security;

import cn.dev33.satoken.stp.StpUtil;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.application.Sessions;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import org.springframework.stereotype.Component;

/**
 * 用 Sa-Token 管理登录会话（D-005），租户在登录时写进会话。也是其他上下文取“当前登录人”的 {@link CurrentUser}：
 * 只在请求线程里能取到，后台线程要先在请求线程里取出来再传过去。
 */
@Component
class SaTokenSessions implements Sessions, CurrentUser {

    /** 会话里存租户的键。 */
    static final String TENANT_KEY = "tenantId";

    @Override
    public String login(long accountId, TenantId tenantId) {
        StpUtil.login(accountId);
        StpUtil.getSession().set(TENANT_KEY, tenantId.value());
        return StpUtil.getTokenValue();
    }

    @Override
    public void logoutCurrent() {
        StpUtil.logout();
    }

    @Override
    public void logoutAll(long accountId) {
        StpUtil.logout(accountId);
    }

    @Override
    public long currentAccountId() {
        return StpUtil.getLoginIdAsLong();
    }

    @Override
    public TenantId currentTenantId() {
        return new TenantId(StpUtil.getSession().getLong(TENANT_KEY));
    }

    @Override
    public UserId userId() {
        return new UserId(currentAccountId());
    }

    @Override
    public TenantId tenantId() {
        return currentTenantId();
    }
}
