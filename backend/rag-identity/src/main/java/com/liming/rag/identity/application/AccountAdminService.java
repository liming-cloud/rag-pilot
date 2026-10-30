package com.liming.rag.identity.application;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号管理：分页筛选、新建、启用停用、分配角色、重置密码、解锁。越权规则见 {@link AssignmentRules}。
 * 新账号与操作人同租户。
 */
@Service
public class AccountAdminService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");

    private final Accounts accounts;
    private final Roles roles;
    private final AccessService access;
    private final Clock clock;

    @Autowired
    AccountAdminService(Accounts accounts, Roles roles, AccessService access) {
        this(accounts, roles, access, Clock.systemUTC());
    }

    AccountAdminService(Accounts accounts, Roles roles, AccessService access, Clock clock) {
        this.accounts = accounts;
        this.roles = roles;
        this.access = access;
        this.clock = clock;
    }

    /** 管理端看到的一个账号：账号本身、角色、是否超级管理员。 */
    public record ManagedAccount(Account account, List<Role> roles, boolean superAdmin) {
    }

    /** @param status enabled、disabled、locked，为空不筛 */
    @Transactional(readOnly = true)
    public Paged<ManagedAccount> page(TenantId tenantId, String keyword, Long roleId, String status, int page,
            int size) {
        var parsed = status == null || status.isBlank() ? null : parseStatus(status);
        var found = accounts.page(tenantId, new AccountQuery(blankToNull(keyword), roleId, parsed,
                OffsetDateTime.now(clock), new Paged.Request(page, size)));
        var assigned = roles.rolesOf(found.items().stream().map(Account::id).toList());
        return found.map(account -> managed(account, assigned.getOrDefault(account.id(), List.of())));
    }

    @Transactional
    public ManagedAccount create(TenantId tenantId, UserId actor, String username, String displayName,
            String password, Collection<Long> roleIds) {
        var name = username == null ? "" : username.strip();
        var display = displayName == null ? "" : displayName.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw invalid("用户名必须是 3 到 50 个字母、数字或 _ . -");
        }
        if (display.isEmpty() || display.length() > 50) {
            throw invalid("显示名必须是 1 到 50 个字符");
        }
        PasswordRules.check(name, password);
        var assigned = rolesIn(tenantId, roleIds);
        rulesFor(actor).checkAssignable(assigned);
        if (accounts.existsByUsername(name)) {
            throw new IdentityException(IdentityException.Reason.CONFLICT, "用户名已被使用：" + name);
        }
        var created = accounts.insert(new Account(null, tenantId, name, display,
                BCrypt.hashpw(password, BCrypt.gensalt()), true, false, 0, null));
        roles.setUserRoles(created.id(), assigned.stream().map(Role::id).toList());
        return managed(created, assigned);
    }

    /** 启用或停用。停用时踢掉它所有已登录的会话，令牌立即失效。不能停用自己。 */
    @Transactional
    public ManagedAccount setEnabled(TenantId tenantId, UserId actor, UserId target, boolean enabled) {
        var account = find(tenantId, target);
        if (!enabled && target.equals(actor)) {
            throw invalid("不能停用自己的账号");
        }
        rulesFor(actor).checkManageable(roles.rolesOf(account.id()));
        accounts.setEnabled(account.id(), enabled);
        if (!enabled) {
            StpUtil.logout(account.id());
        }
        return managed(withEnabled(account, enabled), roles.rolesOf(account.id()));
    }

    /** 整体替换账号的角色，至少一个；权限立即按新角色生效。 */
    @Transactional
    public ManagedAccount assignRoles(TenantId tenantId, UserId actor, UserId target, Collection<Long> roleIds) {
        var account = find(tenantId, target);
        var rules = rulesFor(actor);
        rules.checkManageable(roles.rolesOf(account.id()));
        var assigned = rolesIn(tenantId, roleIds);
        rules.checkAssignable(assigned);
        roles.setUserRoles(account.id(), assigned.stream().map(Role::id).toList());
        return managed(account, assigned);
    }

    /** 重置为临时密码：对方已登录的会话失效，下次登录必须先改密码。超级管理员的密码只能自己改。 */
    @Transactional
    public void resetPassword(TenantId tenantId, UserId actor, UserId target, String password) {
        var account = find(tenantId, target);
        rulesFor(actor).checkManageable(roles.rolesOf(account.id()));
        PasswordRules.check(account.username(), password);
        accounts.setPassword(account.id(), BCrypt.hashpw(password, BCrypt.gensalt()), true);
        StpUtil.logout(account.id());
    }

    /** 手动解锁连续输错被锁的账号。 */
    @Transactional
    public void unlock(TenantId tenantId, UserId target) {
        accounts.clearFailedLogins(find(tenantId, target).id());
    }

    private AssignmentRules rulesFor(UserId actor) {
        var account = accounts.find(actor.value()).orElseThrow(() -> notFound());
        return new AssignmentRules(new LinkedHashSet<>(access.permissionsOf(account)),
                access.superAdmin(account.id()));
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

    private static AccountQuery.Status parseStatus(String status) {
        try {
            return AccountQuery.Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("状态只能是 enabled、disabled 或 locked");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static IdentityException notFound() {
        return new IdentityException(IdentityException.Reason.NOT_FOUND, "账号不存在");
    }

    private static IdentityException invalid(String message) {
        return new IdentityException(IdentityException.Reason.INVALID, message);
    }
}
