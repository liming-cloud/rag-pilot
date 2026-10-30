package com.liming.rag.identity.application;

import com.liming.rag.identity.api.Permissions;
import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色与权限（D-038）。超级管理员只读；内置角色不能改名、不能删；还有账号的角色不能删。
 * 给角色的权限点不能超出操作人自己的（{@link AssignmentRules}）。
 */
@Service
public class RoleService {

    /** 内置“管理员”的默认权限：知识库、用户、回答记录、使用统计的全部权限和问答。 */
    static final Set<String> ADMIN_DEFAULT = Set.of(Permissions.CHAT_USE,
            Permissions.KB_VIEW, Permissions.KB_CREATE, Permissions.KB_UPDATE, Permissions.KB_DELETE,
            Permissions.DOC_UPLOAD, Permissions.DOC_REPLACE, Permissions.DOC_DELETE, Permissions.KB_GRANT,
            Permissions.USER_VIEW, Permissions.USER_CREATE, Permissions.USER_UPDATE, Permissions.USER_DISABLE,
            Permissions.USER_RESET_PASSWORD, Permissions.USER_ASSIGN_ROLE, Permissions.USER_IMPORT,
            Permissions.ANSWER_VIEW, Permissions.ANSWER_REVIEW, Permissions.ANSWER_RECHECK, Permissions.ANSWER_EXPORT,
            Permissions.STATS_VIEW, Permissions.STATS_EXPORT);
    static final Set<String> STAFF_DEFAULT = Set.of(Permissions.CHAT_USE);

    private final Roles roles;
    private final Accounts accounts;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    RoleService(Roles roles, Accounts accounts, AccessService access, ApplicationEventPublisher events) {
        this.roles = roles;
        this.accounts = accounts;
        this.access = access;
        this.events = events;
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
        rulesFor(actor).checkGrantable(granted);
        if (roles.existsByName(tenantId, trimmed)) {
            throw new IdentityException(IdentityException.Reason.CONFLICT, "已有同名角色：" + trimmed);
        }
        return new RoleSummary(roles.insert(new Role(null, tenantId, trimmed, null, granted)), 0);
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
                throw new IdentityException(IdentityException.Reason.CONFLICT, "已有同名角色：" + newName);
            }
        }
        var granted = role.permissions();
        if (permissions != null) {
            granted = permissionsOrEmpty(permissions);
            rulesFor(actor).checkGrantable(granted);
        }
        var updated = new Role(role.id(), tenantId, newName, role.builtIn(), granted);
        roles.update(updated);
        return new RoleSummary(updated, roles.userIds(id).size());
    }

    /** 删除自定义角色；授权给它的知识库由 knowledge 模块监听 {@link RoleDeleted} 一并收回。 */
    @Transactional
    public void delete(TenantId tenantId, long id) {
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
        events.publishEvent(new RoleDeleted(tenantId, id));
    }

    /** 租户里缺少的内置角色按默认权限补上，供首次启动使用。 */
    @Transactional
    public void ensureBuiltIns(TenantId tenantId) {
        for (var builtIn : Role.BuiltIn.values()) {
            if (roles.findBuiltIn(tenantId, builtIn).isEmpty()) {
                var permissions = switch (builtIn) {
                    case SUPER_ADMIN -> Set.<String>of();
                    case ADMIN -> ADMIN_DEFAULT;
                    case STAFF -> STAFF_DEFAULT;
                };
                roles.insert(new Role(null, tenantId, builtIn.defaultName(), builtIn, permissions));
            }
        }
    }

    private AssignmentRules rulesFor(UserId actor) {
        var account = accounts.find(actor.value())
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.NOT_FOUND, "账号不存在"));
        return new AssignmentRules(new LinkedHashSet<>(access.permissionsOf(account)), access.superAdmin(account.id()));
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

    private static IdentityException invalid(String message) {
        return new IdentityException(IdentityException.Reason.INVALID, message);
    }
}
