package com.liming.rag.identity.application;

import cn.dev33.satoken.secure.BCrypt;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Set;

/** 单元测试的初始数据：内置三个角色、一个超级管理员和一个管理员。 */
final class IdentityFixture {

    static final TenantId TENANT = new TenantId(1);
    static final TenantId OTHER_TENANT = new TenantId(2);
    static final String PASSWORD = "test-password";

    final InMemoryIdentity store = new InMemoryIdentity();
    final AccessService access = new AccessService(store, store);
    final Role superRole;
    final Role adminRole;
    final Role staffRole;
    final UserId superAdmin;
    final UserId admin;

    IdentityFixture() {
        superRole = store.insert(new Role(null, TENANT, "超级管理员", Role.BuiltIn.SUPER_ADMIN, Set.of()));
        adminRole = store.insert(new Role(null, TENANT, "管理员", Role.BuiltIn.ADMIN, RoleService.ADMIN_DEFAULT));
        staffRole = store.insert(new Role(null, TENANT, "员工", Role.BuiltIn.STAFF, RoleService.STAFF_DEFAULT));
        superAdmin = account("root", superRole);
        admin = account("admin01", adminRole);
    }

    UserId account(String username, Role... roles) {
        var created = store.insert(new Account(null, TENANT, username, username,
                BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4)), true, false, 0, null));
        store.setUserRoles(created.id(), List.of(roles).stream().map(Role::id).toList());
        return new UserId(created.id());
    }

    Role role(String name, String... permissions) {
        return store.insert(new Role(null, TENANT, name, null, Set.of(permissions)));
    }

    Account get(UserId id) {
        return store.find(id.value()).orElseThrow();
    }
}
