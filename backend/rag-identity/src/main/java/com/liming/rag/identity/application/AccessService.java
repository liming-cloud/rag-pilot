package com.liming.rag.identity.application;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 权限判定：账号的权限点 = 所属角色的权限点之和，超级管理员是全部权限点。每次校验都读库，停用、改角色、改角色权限
 * 立即生效。停用的账号、必须先改密码的账号没有任何权限点，只能调只要求登录的接口（改密码、退出、当前用户）。
 * 同时是 Sa-Token 的权限来源，以及给其他模块的当前用户、账号与角色查询。
 */
@Service
public class AccessService implements CurrentUser, UserDirectory, StpInterface {

    static final String TENANT_KEY = "tenantId";

    private final Accounts accounts;
    private final Roles roles;
    private final PermissionRegistry permissions;

    AccessService(Accounts accounts, Roles roles, PermissionRegistry permissions) {
        this.accounts = accounts;
        this.roles = roles;
        this.permissions = permissions;
    }

    /** 账号现在能用的权限点，按清单顺序。 */
    public List<String> permissionsOf(Account account) {
        if (!account.enabled() || account.mustChangePassword()) {
            return List.of();
        }
        return List.copyOf(grantedBy(roles.rolesOf(account.id())));
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
    AssignmentRules rulesFor(UserId actor) {
        var account = accounts.find(actor.value())
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.NOT_FOUND, "账号不存在"));
        return new AssignmentRules(new LinkedHashSet<>(permissionsOf(account)), superAdmin(account.id()), this);
    }

    /** 全系统的权限点清单。 */
    Set<String> catalog() {
        return permissions.all();
    }

    public boolean superAdmin(long userId) {
        return roles.rolesOf(userId).stream().anyMatch(Role::superAdmin);
    }

    @Override
    public UserId userId() {
        return new UserId(StpUtil.getLoginIdAsLong());
    }

    @Override
    public TenantId tenantId() {
        return new TenantId(StpUtil.getSession().getLong(TENANT_KEY));
    }

    @Override
    public boolean exists(TenantId tenantId, UserId userId) {
        return activeAccount(tenantId, userId) != null;
    }

    @Override
    public List<Long> roleIds(TenantId tenantId, UserId userId) {
        if (activeAccount(tenantId, userId) == null) {
            return List.of();
        }
        return roles.rolesOf(userId.value()).stream().map(Role::id).toList();
    }

    @Override
    public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
        return activeAccount(tenantId, userId) != null && superAdmin(userId.value());
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

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return accounts.find(Long.parseLong(loginId.toString())).map(this::permissionsOf).orElse(List.of());
    }

    /** 不用角色名做校验，接口只认权限点。 */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }

    private Account activeAccount(TenantId tenantId, UserId userId) {
        return accounts.find(userId.value())
                .filter(account -> account.enabled() && account.tenantId().equals(tenantId))
                .orElse(null);
    }
}
