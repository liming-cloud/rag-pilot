package com.liming.rag.identity.web;

import com.liming.rag.identity.application.AccountAdminService.ManagedAccount;
import com.liming.rag.identity.application.AuthService;
import com.liming.rag.identity.application.Role;
import java.time.OffsetDateTime;
import java.util.List;

/** 对外展示的账号信息，不含密码摘要。 */
record UserView(long id, String username, String displayName, boolean enabled, List<RoleRef> roles,
        boolean superAdmin, OffsetDateTime lockedUntil) {

    record RoleRef(long id, String name) {
        static List<RoleRef> of(List<Role> roles) {
            return roles.stream().map(role -> new RoleRef(role.id(), role.name())).toList();
        }
    }

    static UserView of(ManagedAccount managed) {
        var account = managed.account();
        return new UserView(account.id(), account.username(), account.displayName(), account.enabled(),
                RoleRef.of(managed.roles()), managed.superAdmin(), account.lockedUntil());
    }

    /** 当前登录的账号：角色、权限点，以及是否必须先改密码。 */
    record MeView(long id, String username, String displayName, List<RoleRef> roles, List<String> permissions,
            boolean superAdmin, boolean mustChangePassword) {

        static MeView of(AuthService.Me me) {
            var account = me.account();
            return new MeView(account.id(), account.username(), account.displayName(), RoleRef.of(me.roles()),
                    me.permissions(), me.superAdmin(), account.mustChangePassword());
        }
    }
}
