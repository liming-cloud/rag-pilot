package com.liming.rag.identity.application;

import com.liming.rag.shared.TenantId;
import java.util.ArrayList;
import java.util.List;

/** 单元测试用的会话：记下登录、退出、踢下线，当前登录人由测试指定。 */
final class FakeSessions implements Sessions {

    final List<Long> loggedIn = new ArrayList<>();
    final List<Long> kickedOut = new ArrayList<>();
    int currentLogouts;
    Long current;
    TenantId currentTenant;

    @Override
    public String login(long accountId, TenantId tenantId) {
        loggedIn.add(accountId);
        current = accountId;
        currentTenant = tenantId;
        return "token-" + accountId;
    }

    @Override
    public void logoutCurrent() {
        currentLogouts++;
        current = null;
    }

    @Override
    public void logoutAll(long accountId) {
        kickedOut.add(accountId);
    }

    @Override
    public long currentAccountId() {
        if (current == null) {
            throw new IllegalStateException("没有登录");
        }
        return current;
    }

    @Override
    public TenantId currentTenantId() {
        return currentTenant;
    }
}
