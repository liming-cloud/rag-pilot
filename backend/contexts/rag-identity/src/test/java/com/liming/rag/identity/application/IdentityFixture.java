package com.liming.rag.identity.application;

import com.liming.rag.identity.application.account.Account;
import com.liming.rag.identity.application.role.AccessService;
import com.liming.rag.identity.application.role.Role;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Set;

/** 单元测试的初始数据：内置三个角色、一个超级管理员和一个管理员。 */
public final class IdentityFixture {

    public static final TenantId TENANT = new TenantId(1);
    public static final TenantId OTHER_TENANT = new TenantId(2);
    public static final String PASSWORD = "test-password";

    public final InMemoryIdentity store = new InMemoryIdentity();
    public final AccessService access = new AccessService(store, store, TestCatalog.REGISTRY);
    public final FakeSessions sessions = new FakeSessions();
    public final PlainPasswordHasher passwords = new PlainPasswordHasher();
    /** 记下的审计：每条是“操作 说明”（#145 C2）。 */
    public final java.util.List<String> audited = new java.util.ArrayList<>();
    public final com.liming.rag.identity.api.AuditTrail audit =
            (tenantId, actor, action, targetType, targetId, detail) -> audited.add(action + " " + detail);
    public final Role superRole;
    public final Role adminRole;
    public final Role staffRole;
    public final UserId superAdmin;
    public final UserId admin;

    public IdentityFixture() {
        superRole = store.insert(new Role(null, TENANT, "超级管理员", Role.BuiltIn.SUPER_ADMIN, Set.of()));
        adminRole = store.insert(new Role(null, TENANT, "管理员", Role.BuiltIn.ADMIN, TestCatalog.REGISTRY.adminDefaults()));
        staffRole = store.insert(new Role(null, TENANT, "员工", Role.BuiltIn.STAFF, TestCatalog.REGISTRY.staffDefaults()));
        superAdmin = account("root", superRole);
        admin = account("admin01", adminRole);
    }

    public UserId account(String username, Role... roles) {
        var created = store.insert(new Account(null, TENANT, username, username,
                passwords.hash(PASSWORD), true, false, 0, null));
        store.setUserRoles(created.id(), List.of(roles).stream().map(Role::id).toList());
        return new UserId(created.id());
    }

    public Role role(String name, String... permissions) {
        return store.insert(new Role(null, TENANT, name, null, Set.of(permissions)));
    }

    public Account get(UserId id) {
        return store.find(id.value()).orElseThrow();
    }
}
