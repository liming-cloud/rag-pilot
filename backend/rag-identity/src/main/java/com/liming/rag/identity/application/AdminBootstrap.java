package com.liming.rag.identity.application;

import com.liming.rag.identity.api.Roles;
import com.liming.rag.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 账号表为空时，用 {@code RAG_ADMIN_USERNAME} / {@code RAG_ADMIN_PASSWORD} 创建第一个管理员。
 * 已有账号时什么都不做，所以改环境变量不会覆盖已有密码。日志里不出现密码。
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final Accounts accounts;
    private final AccountService service;
    private final TenantId tenant;
    private final String username;
    private final String password;

    AdminBootstrap(Accounts accounts, AccountService service, IdentityProperties properties) {
        this.accounts = accounts;
        this.service = service;
        this.tenant = new TenantId(properties.admin().tenantId());
        this.username = properties.admin().username();
        this.password = properties.admin().password();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.count() > 0) {
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            log.warn("还没有任何账号：请设置 RAG_ADMIN_USERNAME 和 RAG_ADMIN_PASSWORD 后重启，以创建第一个管理员");
            return;
        }
        service.create(tenant, username, "管理员", password, Roles.ADMIN);
        log.info("已创建初始管理员 {}", username);
    }
}
