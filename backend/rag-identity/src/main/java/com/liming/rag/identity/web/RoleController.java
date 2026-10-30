package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
import com.liming.rag.identity.application.Role;
import com.liming.rag.identity.application.RoleService;
import com.liming.rag.identity.application.RoleService.RoleSummary;
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

    record RoleRequest(String name, List<String> permissions) {
    }

    /** 超级管理员的权限点列为全部。 */
    record RoleView(long id, String name, String builtIn, List<String> permissions, long userCount) {
        static RoleView of(RoleSummary summary) {
            Role role = summary.role();
            var permissions = role.superAdmin() ? Permissions.ALL : role.permissions();
            return new RoleView(role.id(), role.name(), role.builtIn() == null ? null : role.builtIn().name(),
                    Permissions.ALL.stream().filter(permissions::contains).toList(), summary.userCount());
        }
    }

    private final RoleService roles;
    private final CurrentUser currentUser;

    RoleController(RoleService roles, CurrentUser currentUser) {
        this.roles = roles;
        this.currentUser = currentUser;
    }

    @SaCheckPermission(Permissions.ROLE_VIEW)
    @GetMapping("/api/permissions")
    List<Permissions.Group> catalog() {
        return Permissions.CATALOG;
    }

    /** 角色页、用户页（分配角色）、知识库授权（授权给角色）都要读角色列表。 */
    @SaCheckPermission(value = {Permissions.ROLE_VIEW, Permissions.USER_VIEW, Permissions.KB_GRANT},
            mode = SaMode.OR)
    @GetMapping("/api/roles")
    List<RoleView> list() {
        return roles.list(currentUser.tenantId()).stream().map(RoleView::of).toList();
    }

    @SaCheckPermission(Permissions.ROLE_CREATE)
    @PostMapping("/api/roles")
    @ResponseStatus(HttpStatus.CREATED)
    RoleView create(@RequestBody RoleRequest request) {
        return RoleView.of(roles.create(currentUser.tenantId(), currentUser.userId(), request.name(),
                request.permissions()));
    }

    @SaCheckPermission(Permissions.ROLE_UPDATE)
    @PatchMapping("/api/roles/{id}")
    RoleView update(@PathVariable long id, @RequestBody RoleRequest request) {
        return RoleView.of(roles.update(currentUser.tenantId(), currentUser.userId(), id, request.name(),
                request.permissions()));
    }

    @SaCheckPermission(Permissions.ROLE_DELETE)
    @DeleteMapping("/api/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        roles.delete(currentUser.tenantId(), id);
    }
}
