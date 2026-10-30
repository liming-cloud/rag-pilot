package com.liming.rag.identity.application;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
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

    AdminBootstrap(Accounts accounts, Roles roles, RoleService roleService, IdentityProperties properties) {
        this.accounts = accounts;
        this.roles = roles;
        this.roleService = roleService;
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
            log.warn("还没有任何账号：请设置 RAG_ADMIN_USERNAME 和 RAG_ADMIN_PASSWORD 后重启，以创建超级管理员");
            return;
        }
        PasswordRules.check(admin.username(), admin.password());
        var created = accounts.insert(new Account(null, tenant, admin.username(), "超级管理员",
                BCrypt.hashpw(admin.password(), BCrypt.gensalt()), true, false, 0, null));
        roles.setUserRoles(created.id(), List.of(superRole.id()));
        log.info("已创建超级管理员 {}", admin.username());
    }

    private void resetSuperAdminPassword(Role superRole) {
        var superAdmins = roles.userIds(superRole.id());
        if (superAdmins.isEmpty()) {
            log.warn("设置了 RAG_ADMIN_RESET_PASSWORD，但还没有超级管理员，没有重置");
            return;
        }
        var account = accounts.find(superAdmins.getFirst()).orElseThrow();
        PasswordRules.check(account.username(), admin.resetPassword());
        accounts.setPassword(account.id(), BCrypt.hashpw(admin.resetPassword(), BCrypt.gensalt()), false);
        accounts.setEnabled(account.id(), true);
        StpUtil.logout(account.id());
        log.warn("已按 RAG_ADMIN_RESET_PASSWORD 重置超级管理员 {} 的密码并解锁；请删掉这个环境变量后重启",
                account.username());
    }
}
