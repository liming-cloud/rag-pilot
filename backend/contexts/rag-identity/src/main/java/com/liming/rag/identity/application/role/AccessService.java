package com.liming.rag.identity.application.role;

import com.liming.rag.identity.application.account.Accounts;
import com.liming.rag.identity.application.account.Account;

import com.liming.rag.identity.application.IdentityException;

import com.liming.rag.cache.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.TypedCache;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 权限判定：账号的权限点 = 所属角色的权限点之和，超级管理员是全部权限点。停用的账号、必须先改密码的账号没有任何
 * 权限点，只能调只要求登录的接口（改密码、退出、当前用户）。同时给其他模块查账号与角色（{@link UserDirectory}）。
 * Sa-Token 的权限来源和“当前登录人”在 {@code infrastructure.security}，都以这里的判定为准。
 *
 * <p>每个账号的状态、角色和权限点缓存成一份 {@link AccessSnapshot}（D-053，修订 D-041 的“每次读库”）。停用、启用、
 * 改角色、改密码、重置密码后由改动方调 {@link #forget(long)}，改角色的权限点、删角色后调 {@link #forgetAll()}，
 * 都在事务提交后清除，下一个请求按新权限判定，仍是立即生效。
 */
@Service
public class AccessService implements UserDirectory {

    /** 过期时间只是兜底，以清除为准。 */
    static final CacheSpec<AccessSnapshot> ACCESS = new CacheSpec<>("access", AccessSnapshot.class, Duration.ofHours(1));

    private final Accounts accounts;
    private final Roles roles;
    private final PermissionRegistry permissions;
    private final TypedCache<AccessSnapshot> snapshots;

    public AccessService(Accounts accounts, Roles roles, PermissionRegistry permissions, Caches caches) {
        this.accounts = accounts;
        this.roles = roles;
        this.permissions = permissions;
        this.snapshots = caches.cache(ACCESS);
    }

    /**
     * 一个账号判定权限要用的全部信息。
     *
     * @param permissions 现在能用的权限点（已按账号状态算好）
     */
    record AccessSnapshot(long accountId, long tenantId, boolean enabled, List<Long> roleIds, boolean superAdmin,
            List<String> permissions) {
    }

    /** 账号的状态、角色或权限变了，提交后清除它的缓存。 */
    public void forget(long accountId) {
        snapshots.evict(String.valueOf(accountId));
    }

    /** 角色的权限点变了或角色删了，影响的账号可能很多，提交后清除全部账号的缓存。 */
    public void forgetAll() {
        snapshots.clear();
    }

    private @Nullable AccessSnapshot snapshot(long accountId) {
        return snapshots.get(String.valueOf(accountId), () -> accounts.find(accountId).map(account -> {
            var assigned = roles.rolesOf(account.id());
            return new AccessSnapshot(account.id(), account.tenantId().value(), account.enabled(),
                    assigned.stream().map(Role::id).toList(), assigned.stream().anyMatch(Role::superAdmin),
                    permissionsOf(account, assigned));
        }).orElse(null));
    }

    private @Nullable AccessSnapshot active(TenantId tenantId, UserId userId) {
        var snapshot = snapshot(userId.value());
        return snapshot != null && snapshot.enabled() && snapshot.tenantId() == tenantId.value() ? snapshot : null;
    }

    /** 账号现在能用的权限点，按清单顺序。 */
    public List<String> permissionsOf(Account account) {
        return permissionsOf(account, roles.rolesOf(account.id()));
    }

    private List<String> permissionsOf(Account account, Collection<Role> assigned) {
        if (!account.enabled() || account.mustChangePassword()) {
            return List.of();
        }
        return List.copyOf(grantedBy(assigned));
    }

    /** 这些角色合起来给出的权限点，不看账号状态；含超级管理员角色时为全部权限点。清单外的权限点不算。 */
    Set<String> grantedBy(Collection<Role> assigned) {
        if (assigned.stream().anyMatch(Role::superAdmin)) {
            return permissions.all();
        }
        var granted = new HashSet<String>();
        assigned.forEach(role -> granted.addAll(role.permissions()));
        return new LinkedHashSet<>(permissions.ordered(granted));
    }

    /**
     * 操作人的越权规则（D-038）：他自己的权限点、是否超级管理员，以及全系统的权限点清单。
     *
     * @throws IdentityException 操作人的账号不存在
     */
    public AssignmentRules rulesFor(UserId actor) {
        var account = accounts.find(actor.value())
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.NOT_FOUND, "账号不存在"));
        return new AssignmentRules(new LinkedHashSet<>(permissionsOf(account)), superAdmin(account.id()), this);
    }

    /** 全系统的权限点清单。 */
    Set<String> catalog() {
        return permissions.all();
    }

    public boolean superAdmin(long userId) {
        var snapshot = snapshot(userId);
        return snapshot != null && snapshot.superAdmin();
    }

    @Override
    public boolean exists(TenantId tenantId, UserId userId) {
        return active(tenantId, userId) != null;
    }

    @Override
    public List<Long> roleIds(TenantId tenantId, UserId userId) {
        var snapshot = active(tenantId, userId);
        return snapshot == null ? List.of() : snapshot.roleIds();
    }

    @Override
    public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
        var snapshot = active(tenantId, userId);
        return snapshot != null && snapshot.superAdmin();
    }

    @Override
    public boolean roleExists(TenantId tenantId, long roleId) {
        return roles.find(tenantId, roleId).isPresent();
    }

    @Override
    public Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findAll(tenantId, userIds).stream()
                .map(account -> new UserSummary(account.id(), account.username(), account.displayName()))
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    /** 账号现在能用的权限点；账号不存在时没有任何权限点。 */
    public List<String> permissionsOf(long accountId) {
        var snapshot = snapshot(accountId);
        return snapshot == null ? List.of() : snapshot.permissions();
    }
}
