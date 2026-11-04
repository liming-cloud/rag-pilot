package com.liming.rag.identity.application;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色与权限（D-038）。超级管理员只读；内置角色不能改名、不能删；还有账号的角色不能删。
 * 给角色的权限点不能超出操作人自己的（{@link AssignmentRules}）。
 */
@Service
public class RoleService {

    private final Roles roles;
    private final Accounts accounts;
    private final AccessService access;
    private final ApplicationEventPublisher events;
    private final PermissionRegistry permissions;
    private final AuditTrail audit;

    RoleService(Roles roles, Accounts accounts, AccessService access, ApplicationEventPublisher events,
            PermissionRegistry permissions, AuditTrail audit) {
        this.audit = audit;
        this.roles = roles;
        this.accounts = accounts;
        this.access = access;
        this.events = events;
        this.permissions = permissions;
    }

    /** 一个角色和属于它的账号数。 */
    public record RoleSummary(Role role, long userCount) {
    }

    @Transactional(readOnly = true)
    public List<RoleSummary> list(TenantId tenantId) {
        var counts = roles.userCounts(tenantId);
        return roles.list(tenantId).stream()
                .map(role -> new RoleSummary(role, counts.getOrDefault(role.id(), 0L)))
                .toList();
    }

    @Transactional
    public RoleSummary create(TenantId tenantId, UserId actor, String name, Collection<String> permissions) {
        var trimmed = checkName(name);
        var granted = permissionsOrEmpty(permissions);
        access.rulesFor(actor).checkGrantable(granted);
        if (roles.existsByName(tenantId, trimmed)) {
            throw nameTaken(trimmed);
        }
        try {
            var created = roles.insert(new Role(null, tenantId, trimmed, null, granted));
            audit.record(tenantId, actor, "ROLE_CREATED", "ROLE", String.valueOf(created.id()),
                    "新建角色 " + trimmed + "，权限点：" + codes(granted));
            return new RoleSummary(created, 0);
        } catch (DuplicateKeyException e) {
            // 查重之后、插入之前别人建了同名角色：靠唯一约束兜底（#135）。
            throw nameTaken(trimmed);
        }
    }

    /** {@code name}、{@code permissions} 为 null 表示不改。 */
    @Transactional
    public RoleSummary update(TenantId tenantId, UserId actor, long id, String name, Collection<String> permissions) {
        var role = find(tenantId, id);
        if (role.superAdmin()) {
            throw invalid("超级管理员的权限不能修改");
        }
        var newName = role.name();
        if (name != null && !name.strip().equals(role.name())) {
            if (role.builtIn() != null) {
                throw invalid("内置角色不能改名");
            }
            newName = checkName(name);
            if (roles.existsByName(tenantId, newName)) {
                throw nameTaken(newName);
            }
        }
        var granted = role.permissions();
        if (permissions != null) {
            granted = permissionsOrEmpty(permissions);
            access.rulesFor(actor).checkGrantable(granted);
        }
        var updated = new Role(role.id(), tenantId, newName, role.builtIn(), granted);
        try {
            roles.update(updated);
        } catch (DuplicateKeyException e) {
            throw nameTaken(newName);
        }
        audit.record(tenantId, actor, "ROLE_UPDATED", "ROLE", String.valueOf(id), "角色 " + role.name()
                + (newName.equals(role.name()) ? "" : " 改名为 " + newName) + "，权限点：" + codes(granted));
        return new RoleSummary(updated, roles.userIds(id).size());
    }

    /** 删除自定义角色；授权给它的知识库由 knowledge 模块监听 {@link RoleDeleted} 一并收回。 */
    @Transactional
    public void delete(TenantId tenantId, UserId actor, long id) {
        var role = find(tenantId, id);
        if (role.builtIn() != null) {
            throw invalid("内置角色不能删除");
        }
        int users = roles.userIds(id).size();
        if (users > 0) {
            throw new IdentityException(IdentityException.Reason.CONFLICT,
                    "还有 " + users + " 个账号属于这个角色，先移走才能删除");
        }
        roles.delete(id);
        audit.record(tenantId, actor, "ROLE_DELETED", "ROLE", String.valueOf(id), "删除角色 " + role.name());
        events.publishEvent(new RoleDeleted(tenantId, id));
    }

    /** 租户里缺少的内置角色按默认权限补上，供首次启动使用。 */
    @Transactional
    public void ensureBuiltIns(TenantId tenantId) {
        for (var builtIn : Role.BuiltIn.values()) {
            if (roles.findBuiltIn(tenantId, builtIn).isEmpty()) {
                var defaults = switch (builtIn) {
                    case SUPER_ADMIN -> Set.<String>of();
                    case ADMIN -> permissions.adminDefaults();
                    case STAFF -> permissions.staffDefaults();
                };
                roles.insert(new Role(null, tenantId, builtIn.defaultName(), builtIn, defaults));
            }
        }
    }

    private Role find(TenantId tenantId, long id) {
        return roles.find(tenantId, id)
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.NOT_FOUND, "角色不存在"));
    }

    private static String checkName(String name) {
        var trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 20) {
            throw invalid("角色名称必须是 1 到 20 个字符");
        }
        return trimmed;
    }

    private static Set<String> permissionsOrEmpty(Collection<String> permissions) {
        return permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    private static IdentityException nameTaken(String name) {
        return new IdentityException(IdentityException.Reason.CONFLICT, "已有同名角色：" + name);
    }

    private static IdentityException invalid(String message) {
        return new IdentityException(IdentityException.Reason.INVALID, message);
    }

    private static String codes(java.util.Set<String> granted) {
        return granted.isEmpty() ? "无" : String.join("、", new java.util.TreeSet<>(granted));
    }
}
