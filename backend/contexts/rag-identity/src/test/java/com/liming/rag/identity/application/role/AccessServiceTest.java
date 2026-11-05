package com.liming.rag.identity.application.role;

import com.liming.rag.identity.application.TestCatalog;
import com.liming.rag.identity.application.IdentityFixture;


import static com.liming.rag.identity.application.IdentityFixture.OTHER_TENANT;
import static com.liming.rag.identity.application.IdentityFixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 权限点 = 所属角色的权限点之和；结果缓存，改动方清除后按当前数据算（D-053）。 */
class AccessServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final AccessService access = fixture.access;

    @Test
    @DisplayName("多个角色的权限点合并，按清单顺序；清单外的权限点不算")
    void unionOfRoles() {
        var manager = fixture.role("店长", TestCatalog.ANSWER_VIEW, "legacy:old");
        var user = fixture.account("zhang", fixture.staffRole, manager);

        assertThat(access.permissionsOf(user.value()))
                .containsExactly(TestCatalog.CHAT_USE, TestCatalog.ANSWER_VIEW);
    }

    @Test
    @DisplayName("超级管理员拥有清单里的全部权限点")
    void superAdminHasAll() {
        assertThat(access.permissionsOf(fixture.superAdmin.value()))
                .containsExactlyElementsOf(TestCatalog.REGISTRY.all());
        assertThat(access.isSuperAdmin(TENANT, fixture.superAdmin)).isTrue();
        assertThat(access.isSuperAdmin(TENANT, fixture.admin)).isFalse();
    }

    @Test
    @DisplayName("停用的账号、必须先改密码的账号没有任何权限点，也不参与按角色的授权")
    void inactiveAccountsHaveNothing() {
        var staff = fixture.account("staff01", fixture.staffRole);
        fixture.store.setPassword(staff.value(), "hash", true);
        access.forget(staff.value());
        assertThat(access.permissionsOf(staff.value())).isEmpty();

        fixture.store.setPassword(staff.value(), "hash", false);
        access.forget(staff.value());
        assertThat(access.permissionsOf(staff.value())).containsExactly(TestCatalog.CHAT_USE);

        fixture.store.setEnabled(staff.value(), false);
        access.forget(staff.value());
        assertThat(access.permissionsOf(staff.value())).isEmpty();
        assertThat(access.roleIds(TENANT, staff)).isEmpty();
        assertThat(access.exists(TENANT, staff)).isFalse();
    }

    @Test
    @DisplayName("改了角色的权限点、清除后立即生效；其他租户查不到这个账号的角色")
    void followsCurrentData() {
        var manager = fixture.role("店长", TestCatalog.ANSWER_VIEW);
        var user = fixture.account("zhang", manager);
        assertThat(access.roleIds(TENANT, user)).containsExactly(manager.id());
        assertThat(access.roleIds(OTHER_TENANT, user)).isEmpty();

        fixture.store.update(new Role(manager.id(), TENANT, "店长", null,
                java.util.Set.of(TestCatalog.ANSWER_VIEW, TestCatalog.STATS_VIEW)));
        access.forgetAll();

        assertThat(access.permissionsOf(user.value()))
                .isEqualTo(List.of(TestCatalog.ANSWER_VIEW, TestCatalog.STATS_VIEW));
    }

    @Test
    @DisplayName("权限按账号缓存：不经清除直接改库时，读到的还是缓存里的；清除一个账号不影响别的账号")
    void cachedUntilForgotten() {
        var staff = fixture.account("staff01", fixture.staffRole);
        var other = fixture.account("staff02", fixture.staffRole);
        assertThat(access.permissionsOf(staff.value())).containsExactly(TestCatalog.CHAT_USE);
        assertThat(access.permissionsOf(other.value())).containsExactly(TestCatalog.CHAT_USE);

        fixture.store.setEnabled(staff.value(), false);
        fixture.store.setEnabled(other.value(), false);
        assertThat(access.permissionsOf(staff.value())).containsExactly(TestCatalog.CHAT_USE);

        access.forget(staff.value());
        assertThat(access.permissionsOf(staff.value())).isEmpty();
        assertThat(access.permissionsOf(other.value())).containsExactly(TestCatalog.CHAT_USE);
    }
}
