package com.liming.rag.identity.application;

import static com.liming.rag.identity.application.IdentityFixture.OTHER_TENANT;
import static com.liming.rag.identity.application.IdentityFixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.secure.BCrypt;
import com.liming.rag.identity.api.Permissions;
import com.liming.rag.shared.ErrorKind;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 账号管理与防止越权分配（D-038）。 */
class AccountAdminServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final AccountAdminService service = new AccountAdminService(fixture.store, fixture.store, fixture.access);

    @Test
    @DisplayName("新建账号：去掉首尾空格，密码只存 BCrypt 摘要，带上角色")
    void createsAccount() {
        var created = service.create(TENANT, fixture.admin, "  staff01 ", " 店员小王 ", "staff-password",
                List.of(fixture.staffRole.id()));

        var account = created.account();
        assertThat(account.username()).isEqualTo("staff01");
        assertThat(account.displayName()).isEqualTo("店员小王");
        assertThat(account.passwordHash()).isNotEqualTo("staff-password");
        assertThat(BCrypt.checkpw("staff-password", account.passwordHash())).isTrue();
        assertThat(created.roles()).containsExactly(fixture.staffRole);
        assertThat(fixture.store.rolesOf(account.id())).containsExactly(fixture.staffRole);
    }

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @CsvSource({
        "ab, 门店员工, staff-password, 用户名",
        "staff01, '', staff-password, 显示名",
        "staff01, 门店员工, short, 8 到 64",
        "storekeeper, 门店员工, StoreKeeper, 用户名相同",
    })
    @DisplayName("用户名、显示名、密码不合规时拒绝")
    void rejectsInvalidInput(String username, String displayName, String password, String hint) {
        assertThatThrownBy(() -> service.create(TENANT, fixture.admin, username, displayName, password,
                List.of(fixture.staffRole.id())))
                .isInstanceOf(IdentityException.class).hasMessageContaining(hint);
        assertThat(fixture.store.existsByUsername(username)).isFalse();
    }

    @Test
    @DisplayName("至少一个角色；其他租户的角色当作不存在")
    void requiresRolesOfTheTenant() {
        assertThatThrownBy(() -> service.create(TENANT, fixture.admin, "staff01", "店员", "staff-password",
                List.of())).hasMessage("至少要有一个角色");
        var foreign = fixture.store.insert(new Role(null, OTHER_TENANT, "外部", null, java.util.Set.of()));
        assertThatThrownBy(() -> service.create(TENANT, fixture.admin, "staff01", "店员", "staff-password",
                List.of(foreign.id()))).hasMessage("角色不存在");
    }

    @Test
    @DisplayName("只能分配不超过自己权限的角色；含“角色与权限”的角色只有超级管理员能分配；超级管理员角色谁都不能分配")
    void cannotAssignBeyondOwnPermissions() {
        var auditor = fixture.role("审计", Permissions.MODEL_VIEW);
        var roleAdmin = fixture.role("角色管理员", Permissions.ROLE_VIEW);

        assertEscalation(() -> service.create(TENANT, fixture.admin, "x01", "x", "staff-password",
                List.of(auditor.id())), "超出自己权限");
        assertEscalation(() -> service.create(TENANT, fixture.admin, "x02", "x", "staff-password",
                List.of(fixture.superRole.id())), "超级管理员角色");
        assertEscalation(() -> service.create(TENANT, fixture.superAdmin, "x03", "x", "staff-password",
                List.of(fixture.superRole.id())), "超级管理员角色");
        assertThat(fixture.store.existsByUsername("x01")).isFalse();

        var byAdmin = fixture.account("x04", fixture.staffRole);
        assertEscalation(() -> service.assignRoles(TENANT, fixture.admin, byAdmin, List.of(roleAdmin.id())),
                "只有超级管理员");
        assertThat(fixture.store.rolesOf(byAdmin.value())).containsExactly(fixture.staffRole);

        var bySuper = service.assignRoles(TENANT, fixture.superAdmin, byAdmin, List.of(roleAdmin.id(), auditor.id()));
        assertThat(bySuper.roles()).containsExactly(roleAdmin, auditor);
    }

    @Test
    @DisplayName("不能停用、重置密码、改角色的对象是权限不少于自己的账号；超级管理员别人不能改")
    void cannotManagePeersOrSuperAdmin() {
        var otherAdmin = fixture.account("admin02", fixture.adminRole);

        assertEscalation(() -> service.setEnabled(TENANT, fixture.admin, otherAdmin, false), "不少于自己");
        assertEscalation(() -> service.resetPassword(TENANT, fixture.admin, otherAdmin, "new-password"),
                "不少于自己");
        assertEscalation(() -> service.assignRoles(TENANT, fixture.admin, otherAdmin,
                List.of(fixture.staffRole.id())), "不少于自己");
        assertEscalation(() -> service.setEnabled(TENANT, fixture.admin, fixture.superAdmin, false), "超级管理员");
        assertEscalation(() -> service.resetPassword(TENANT, fixture.admin, fixture.superAdmin, "new-password"),
                "超级管理员");
        assertEscalation(() -> service.assignRoles(TENANT, fixture.admin, fixture.superAdmin,
                List.of(fixture.staffRole.id())), "超级管理员");
        assertThat(fixture.get(otherAdmin).enabled()).isTrue();
        assertThat(fixture.get(fixture.superAdmin).enabled()).isTrue();

        // 超级管理员可以管理其他管理员。
        assertThat(service.setEnabled(TENANT, fixture.superAdmin, otherAdmin, false).account().enabled()).isFalse();
    }

    @Test
    @DisplayName("不能停用自己")
    void cannotDisableSelf() {
        assertThatThrownBy(() -> service.setEnabled(TENANT, fixture.superAdmin, fixture.superAdmin, false))
                .hasMessage("不能停用自己的账号");
    }

    @Test
    @DisplayName("重置密码后必须先改密码，并清掉锁定；解锁清掉失败计数")
    void resetPasswordAndUnlock() {
        var staff = fixture.account("staff01", fixture.staffRole);
        fixture.store.recordFailedLogin(staff.value(), 0, java.time.OffsetDateTime.now().plusMinutes(10));

        service.resetPassword(TENANT, fixture.admin, staff, "temp-password");

        var reset = fixture.get(staff);
        assertThat(reset.mustChangePassword()).isTrue();
        assertThat(reset.lockedUntil()).isNull();
        assertThat(BCrypt.checkpw("temp-password", reset.passwordHash())).isTrue();

        fixture.store.recordFailedLogin(staff.value(), 3, java.time.OffsetDateTime.now().plusMinutes(10));
        service.unlock(TENANT, staff);
        assertThat(fixture.get(staff).failedAttempts()).isZero();
        assertThat(fixture.get(staff).lockedUntil()).isNull();
    }

    @Test
    @DisplayName("跨租户的账号当作不存在")
    void otherTenantNotFound() {
        assertThatThrownBy(() -> service.unlock(OTHER_TENANT, fixture.admin))
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> assertThat(((IdentityException) error).kind()).isEqualTo(ErrorKind.NOT_FOUND));
        assertThatThrownBy(() -> service.unlock(TENANT, new UserId(999))).hasMessage("账号不存在");
    }

    private static void assertEscalation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String hint) {
        assertThatThrownBy(call)
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining(hint)
                .satisfies(error -> assertThat(((IdentityException) error).kind()).isEqualTo(ErrorKind.FORBIDDEN));
    }
}
