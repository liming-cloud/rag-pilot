package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.identity.application.role.PermissionRegistry;
import com.liming.rag.identity.application.role.Role;
import com.liming.rag.identity.application.role.RoleService;
import com.liming.rag.identity.application.role.RoleSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 角色与权限点清单（D-038）。 */
@RestController
@SaCheckLogin
class RoleController {

    /**
     * 知识库授权（knowledge 声明的 {@code kb:grant}）也要读角色列表。identity 是上游，不依赖 knowledge，这里只写编码；
     * 它是否在清单里由 {@code ArchitectureRulesTest} 核对。
     */
    private static final String KB_GRANT = "kb:grant";

    record RoleRequest(String name, List<String> permissions) {
    }

    /** 超级管理员的权限点列为全部。 */
    record RoleView(long id, String name, String builtIn, List<String> permissions, long userCount) {
        static RoleView of(RoleSummary summary, PermissionRegistry catalog) {
            Role role = summary.role();
            var permissions = role.superAdmin() ? catalog.all() : role.permissions();
            return new RoleView(role.id(), role.name(), role.builtIn() == null ? null : role.builtIn().name(),
                    catalog.ordered(permissions), summary.userCount());
        }
    }

    private final RoleService roles;
    private final CurrentUser currentUser;
    private final PermissionRegistry catalog;

    RoleController(RoleService roles, CurrentUser currentUser, PermissionRegistry catalog) {
        this.roles = roles;
        this.currentUser = currentUser;
        this.catalog = catalog;
    }

    @SaCheckPermission(IdentityPermissions.ROLE_VIEW)
    @GetMapping("/api/permissions")
    List<PermissionCatalog.Group> catalog() {
        return catalog.groups();
    }

    /** 角色页、用户页（分配角色）、知识库授权（授权给角色）都要读角色列表。 */
    @SaCheckPermission(value = {IdentityPermissions.ROLE_VIEW, IdentityPermissions.USER_VIEW, KB_GRANT}, mode = SaMode.OR)
    @GetMapping("/api/roles")
    List<RoleView> list() {
        return roles.list(currentUser.tenantId()).stream().map(summary -> RoleView.of(summary, catalog)).toList();
    }

    @SaCheckPermission(IdentityPermissions.ROLE_CREATE)
    @PostMapping("/api/roles")
    @ResponseStatus(HttpStatus.CREATED)
    RoleView create(@RequestBody RoleRequest request) {
        return RoleView.of(roles.create(currentUser.tenantId(), currentUser.userId(), request.name(),
                request.permissions()), catalog);
    }

    @SaCheckPermission(IdentityPermissions.ROLE_UPDATE)
    @PatchMapping("/api/roles/{id}")
    RoleView update(@PathVariable long id, @RequestBody RoleRequest request) {
        return RoleView.of(roles.update(currentUser.tenantId(), currentUser.userId(), id, request.name(),
                request.permissions()), catalog);
    }

    @SaCheckPermission(IdentityPermissions.ROLE_DELETE)
    @DeleteMapping("/api/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        roles.delete(currentUser.tenantId(), currentUser.userId(), id);
    }
}
