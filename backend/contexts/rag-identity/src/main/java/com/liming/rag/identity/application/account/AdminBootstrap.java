package com.liming.rag.identity.application.account;

import com.liming.rag.identity.application.role.AccessService;
import com.liming.rag.identity.application.role.Role;
import com.liming.rag.identity.application.role.Roles;
import com.liming.rag.identity.application.role.RoleService;
import com.liming.rag.identity.application.auth.PasswordHasher;
import com.liming.rag.identity.application.auth.PasswordRules;
import com.liming.rag.identity.application.auth.Sessions;
import com.liming.rag.identity.application.IdentityProperties;

import com.liming.rag.shared.TenantId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时：补齐租户的内置角色；账号表为空时，用 {@code RAG_ADMIN_USERNAME} / {@code RAG_ADMIN_PASSWORD} 创建超级管理员
 * （全系统只有这一个）；设置了 {@code RAG_ADMIN_RESET_PASSWORD} 时把超级管理员的密码重置为它（D-038，密码丢失时由运维
 * 操作，不提供网页找回）。已有账号时改用户名、密码变量不会覆盖已有密码。日志里不出现密码。
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final Accounts accounts;
    private final Roles roles;
    private final RoleService roleService;
    private final TenantId tenant;
    private final IdentityProperties.Admin admin;
    private final Sessions sessions;
    private final PasswordHasher passwords;
    private final AccessService access;

    AdminBootstrap(Accounts accounts, Roles roles, RoleService roleService, IdentityProperties properties,
            Sessions sessions, PasswordHasher passwords, AccessService access) {
        this.accounts = accounts;
        this.roles = roles;
        this.roleService = roleService;
        this.sessions = sessions;
        this.passwords = passwords;
        this.access = access;
        this.tenant = new TenantId(properties.admin().tenantId());
        this.admin = properties.admin();
    }

    @Override
    public void run(ApplicationArguments args) {
        roleService.ensureBuiltIns(tenant);
        var superRole = roles.findBuiltIn(tenant, Role.BuiltIn.SUPER_ADMIN).orElseThrow();
        if (accounts.count() == 0) {
            createSuperAdmin(superRole);
        }
        if (!admin.resetPassword().isBlank()) {
            resetSuperAdminPassword(superRole);
        }
    }

    private void createSuperAdmin(Role superRole) {
        if (admin.username().isBlank() || admin.password().isBlank()) {
            // 还没有任何账号：设置 RAG_ADMIN_USERNAME 和 RAG_ADMIN_PASSWORD 后重启，才会创建超级管理员。
            log.warn("event=admin_missing");
            return;
        }
        PasswordRules.check(admin.username(), admin.password());
        var created = accounts.insert(new Account(null, tenant, admin.username(), "超级管理员",
                passwords.hash(admin.password()), true, false, 0, null));
        roles.setUserRoles(created.id(), List.of(superRole.id()));
        log.info("event=admin_created username={}", admin.username());
    }

    private void resetSuperAdminPassword(Role superRole) {
        var superAdmins = roles.userIds(superRole.id());
        if (superAdmins.isEmpty()) {
            log.warn("event=admin_reset_skipped reason=no_admin");
            return;
        }
        var account = accounts.find(superAdmins.getFirst()).orElseThrow();
        PasswordRules.check(account.username(), admin.resetPassword());
        accounts.setPassword(account.id(), passwords.hash(admin.resetPassword()), false);
        accounts.setEnabled(account.id(), true);
        access.forget(account.id());
        sessions.logoutAll(account.id());
        // 重置后要删掉 RAG_ADMIN_RESET_PASSWORD 再重启，否则每次启动都会重置。
        log.warn("event=admin_password_reset username={}", account.username());
    }
}
