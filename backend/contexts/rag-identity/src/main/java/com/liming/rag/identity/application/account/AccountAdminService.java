package com.liming.rag.identity.application.account;

import com.liming.rag.identity.application.role.AccessService;

import com.liming.rag.identity.application.role.Role;
import com.liming.rag.identity.application.role.Roles;
import com.liming.rag.identity.application.role.AssignmentRules;
import com.liming.rag.identity.application.auth.PasswordHasher;
import com.liming.rag.identity.application.auth.PasswordRules;
import com.liming.rag.identity.application.auth.Sessions;
import com.liming.rag.identity.application.IdentityException;
import com.liming.rag.shared.Enums;
import com.liming.rag.shared.Paged;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.Texts;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号管理：分页筛选、新建、启用停用、分配角色、重置密码、解锁。越权规则见 {@link AssignmentRules}。
 * 新账号与操作人同租户。
 */
@Service
public class AccountAdminService {

    private final Accounts accounts;
    private final Roles roles;
    private final AccessService access;
    private final Sessions sessions;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final AuditTrail audit;

    AccountAdminService(Accounts accounts, Roles roles, AccessService access, Sessions sessions,
            PasswordHasher passwords, Clock clock, AuditTrail audit) {
        this.audit = audit;
        this.accounts = accounts;
        this.roles = roles;
        this.access = access;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
    }

    /** @param status enabled、disabled、locked，为空不筛 */
    @Transactional(readOnly = true)
    public Paged<ManagedAccount> page(TenantId tenantId, @Nullable String keyword, @Nullable Long roleId,
            @Nullable String status, Paged.Request page) {
        var parsed = Enums.parseOrNull(AccountQuery.Status.class, status,
                () -> invalid("状态只能是 enabled、disabled 或 locked"));
        var found = accounts.page(tenantId, new AccountQuery(Texts.blankToNull(keyword), roleId, parsed,
                OffsetDateTime.now(clock), page));
        var assigned = roles.rolesOf(found.items().stream().map(Account::id).toList());
        return found.map(account -> managed(account, assigned.getOrDefault(account.id(), List.of())));
    }

    @Transactional
    public ManagedAccount create(TenantId tenantId, UserId actor, String username, String displayName,
            String password, Collection<Long> roleIds) {
        var name = username == null ? "" : username.strip();
        var display = displayName == null ? "" : displayName.strip();
        var problem = AccountRules.usernameProblem(name);
        if (problem == null) {
            problem = AccountRules.displayNameProblem(display);
        }
        if (problem != null) {
            throw invalid(problem);
        }
        PasswordRules.check(name, password);
        var assigned = rolesIn(tenantId, roleIds);
        access.rulesFor(actor).checkAssignable(assigned);
        if (accounts.existsByUsername(name)) {
            throw usernameTaken(name);
        }
        Account created;
        try {
            created = accounts.insert(new Account(null, tenantId, name, display,
                    passwords.hash(password), true, false, 0, null));
        } catch (DuplicateKeyException e) {
            // 查重之后、插入之前别人用了这个用户名：靠唯一约束兜底（#135）。
            throw usernameTaken(name);
        }
        roles.setUserRoles(created.id(), assigned.stream().map(Role::id).toList());
        audit(tenantId, actor, "ACCOUNT_CREATED", created, "新建账号 " + name + "（" + display + "），角色：" + names(assigned));
        return managed(created, assigned);
    }

    /** 启用或停用。停用时踢掉它所有已登录的会话，令牌立即失效。不能停用自己。 */
    @Transactional
    public ManagedAccount setEnabled(TenantId tenantId, UserId actor, UserId target, boolean enabled) {
        var account = find(tenantId, target);
        if (!enabled && target.equals(actor)) {
            throw invalid("不能停用自己的账号");
        }
        access.rulesFor(actor).checkManageable(roles.rolesOf(account.id()));
        accounts.setEnabled(account.id(), enabled);
        access.forget(account.id());
        audit(tenantId, actor, enabled ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED", account,
                (enabled ? "启用账号 " : "停用账号 ") + account.username());
        if (!enabled) {
            sessions.logoutAll(account.id());
        }
        return managed(withEnabled(account, enabled), roles.rolesOf(account.id()));
    }

    /** 整体替换账号的角色，至少一个；权限立即按新角色生效。 */
    @Transactional
    public ManagedAccount assignRoles(TenantId tenantId, UserId actor, UserId target, Collection<Long> roleIds) {
        var account = find(tenantId, target);
        var rules = access.rulesFor(actor);
        rules.checkManageable(roles.rolesOf(account.id()));
        var assigned = rolesIn(tenantId, roleIds);
        rules.checkAssignable(assigned);
        roles.setUserRoles(account.id(), assigned.stream().map(Role::id).toList());
        access.forget(account.id());
        audit(tenantId, actor, "ACCOUNT_ROLES_CHANGED", account,
                "账号 " + account.username() + " 的角色改为：" + names(assigned));
        return managed(account, assigned);
    }

    /** 重置为临时密码：对方已登录的会话失效，下次登录必须先改密码。超级管理员的密码只能自己改。 */
    @Transactional
    public void resetPassword(TenantId tenantId, UserId actor, UserId target, String password) {
        var account = find(tenantId, target);
        access.rulesFor(actor).checkManageable(roles.rolesOf(account.id()));
        PasswordRules.check(account.username(), password);
        accounts.setPassword(account.id(), passwords.hash(password), true);
        access.forget(account.id());
        sessions.logoutAll(account.id());
        audit(tenantId, actor, "ACCOUNT_PASSWORD_RESET", account, "重置了账号 " + account.username() + " 的密码");
    }

    /** 手动解锁连续输错被锁的账号。 */
    @Transactional
    public void unlock(TenantId tenantId, UserId actor, UserId target) {
        var account = find(tenantId, target);
        accounts.clearFailedLogins(account.id());
        audit(tenantId, actor, "ACCOUNT_UNLOCKED", account, "解锁账号 " + account.username());
    }

    private void audit(TenantId tenantId, UserId actor, String action, Account account, String detail) {
        audit.record(tenantId, actor, action, "ACCOUNT", String.valueOf(account.id()), detail);
    }

    static String names(Collection<Role> roles) {
        return roles.isEmpty() ? "无" : String.join("、", roles.stream().map(Role::name).toList());
    }

    private Account find(TenantId tenantId, UserId target) {
        return accounts.find(target.value())
                .filter(account -> account.tenantId().equals(tenantId))
                .orElseThrow(AccountAdminService::notFound);
    }

    private List<Role> rolesIn(TenantId tenantId, Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw invalid("至少要有一个角色");
        }
        return roleIds.stream().distinct()
                .map(id -> roles.find(tenantId, id)
                        .orElseThrow(() -> new IdentityException(IdentityException.Reason.NOT_FOUND, "角色不存在")))
                .toList();
    }

    private static ManagedAccount managed(Account account, List<Role> assigned) {
        return new ManagedAccount(account, assigned, assigned.stream().anyMatch(Role::superAdmin));
    }

    private static Account withEnabled(Account account, boolean enabled) {
        return new Account(account.id(), account.tenantId(), account.username(), account.displayName(),
                account.passwordHash(), enabled, account.mustChangePassword(), account.failedAttempts(),
                account.lockedUntil());
    }

    private static IdentityException usernameTaken(String username) {
        return new IdentityException(IdentityException.Reason.CONFLICT, "用户名已被使用：" + username);
    }

    private static IdentityException notFound() {
        return new IdentityException(IdentityException.Reason.NOT_FOUND, "账号不存在");
    }

    private static IdentityException invalid(String message) {
        return new IdentityException(IdentityException.Reason.INVALID, message);
    }
}
