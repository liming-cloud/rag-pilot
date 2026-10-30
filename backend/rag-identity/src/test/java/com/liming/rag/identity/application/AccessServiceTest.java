package com.liming.rag.identity.application;

import static com.liming.rag.identity.application.IdentityFixture.OTHER_TENANT;
import static com.liming.rag.identity.application.IdentityFixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.identity.api.Permissions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 权限点 = 所属角色的权限点之和；每次校验都按当前数据算。 */
class AccessServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final AccessService access = fixture.access;

    @Test
    @DisplayName("多个角色的权限点合并，按清单顺序；清单外的权限点不算")
    void unionOfRoles() {
        var manager = fixture.role("店长", Permissions.ANSWER_VIEW, "legacy:old");
        var user = fixture.account("zhang", fixture.staffRole, manager);

        assertThat(access.getPermissionList(user.value(), "login"))
                .containsExactly(Permissions.CHAT_USE, Permissions.ANSWER_VIEW);
    }

    @Test
    @DisplayName("超级管理员拥有清单里的全部权限点")
    void superAdminHasAll() {
        assertThat(access.getPermissionList(fixture.superAdmin.value(), "login"))
                .containsExactlyElementsOf(Permissions.ALL);
        assertThat(access.isSuperAdmin(TENANT, fixture.superAdmin)).isTrue();
        assertThat(access.isSuperAdmin(TENANT, fixture.admin)).isFalse();
    }

    @Test
    @DisplayName("停用的账号、必须先改密码的账号没有任何权限点，也不参与按角色的授权")
    void inactiveAccountsHaveNothing() {
        var staff = fixture.account("staff01", fixture.staffRole);
        fixture.store.setPassword(staff.value(), "hash", true);
        assertThat(access.getPermissionList(staff.value(), "login")).isEmpty();

        fixture.store.setPassword(staff.value(), "hash", false);
        assertThat(access.getPermissionList(staff.value(), "login")).containsExactly(Permissions.CHAT_USE);

        fixture.store.setEnabled(staff.value(), false);
        assertThat(access.getPermissionList(staff.value(), "login")).isEmpty();
        assertThat(access.roleIds(TENANT, staff)).isEmpty();
        assertThat(access.exists(TENANT, staff)).isFalse();
    }

    @Test
    @DisplayName("改了角色的权限点立即生效；其他租户查不到这个账号的角色")
    void followsCurrentData() {
        var manager = fixture.role("店长", Permissions.ANSWER_VIEW);
        var user = fixture.account("zhang", manager);
        assertThat(access.roleIds(TENANT, user)).containsExactly(manager.id());
        assertThat(access.roleIds(OTHER_TENANT, user)).isEmpty();

        fixture.store.update(new Role(manager.id(), TENANT, "店长", null,
                java.util.Set.of(Permissions.ANSWER_VIEW, Permissions.STATS_VIEW)));

        assertThat(access.getPermissionList(user.value(), "login"))
                .isEqualTo(List.of(Permissions.ANSWER_VIEW, Permissions.STATS_VIEW));
    }
}
