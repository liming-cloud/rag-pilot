package com.liming.rag.identity.application;

import static com.liming.rag.identity.application.IdentityFixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.identity.api.Permissions;
import com.liming.rag.identity.api.RoleDeleted;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 角色规则（D-038）。 */
class RoleServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final List<Object> events = new ArrayList<>();
    private final RoleService service = new RoleService(fixture.store, fixture.store, fixture.access, events::add);

    @Test
    @DisplayName("超级管理员只读，内置角色不能改名、不能删，但能改权限")
    void builtInRules() {
        assertThatThrownBy(() -> service.update(TENANT, fixture.superAdmin, fixture.superRole.id(), null,
                List.of(Permissions.CHAT_USE))).hasMessage("超级管理员的权限不能修改");
        assertThatThrownBy(() -> service.update(TENANT, fixture.superAdmin, fixture.staffRole.id(), "店员", null))
                .hasMessage("内置角色不能改名");
        assertThatThrownBy(() -> service.delete(TENANT, fixture.staffRole.id())).hasMessage("内置角色不能删除");

        var updated = service.update(TENANT, fixture.superAdmin, fixture.staffRole.id(), "员工",
                List.of(Permissions.CHAT_USE, Permissions.STATS_VIEW));
        assertThat(updated.role().permissions()).containsExactlyInAnyOrder(Permissions.CHAT_USE, Permissions.STATS_VIEW);
    }

    @Test
    @DisplayName("给角色的权限点不能超出自己的，也不能是清单外的")
    void cannotGrantBeyondOwn() {
        var admin = fixture.access.getPermissionList(fixture.admin.value(), "login");
        assertThat(admin).doesNotContain(Permissions.MODEL_VIEW);

        assertThatThrownBy(() -> service.create(TENANT, fixture.admin, "模型员", List.of(Permissions.MODEL_VIEW)))
                .hasMessage("不能给角色超出自己权限的权限点");
        assertThatThrownBy(() -> service.create(TENANT, fixture.superAdmin, "怪角色", List.of("kb:fly")))
                .hasMessage("权限点不在清单里");
        assertThat(fixture.store.existsByName(TENANT, "模型员")).isFalse();

        var manager = service.create(TENANT, fixture.admin, "店长", List.of(Permissions.ANSWER_VIEW));
        assertThat(manager.role().permissions()).containsExactly(Permissions.ANSWER_VIEW);
        assertThatThrownBy(() -> service.create(TENANT, fixture.admin, "店长", List.of()))
                .hasMessage("已有同名角色：店长");
    }

    @Test
    @DisplayName("还有账号的角色不能删；删除后发布 RoleDeleted，由知识库收回授权")
    void deleteRules() {
        var manager = fixture.role("店长", Permissions.ANSWER_VIEW);
        var zhang = fixture.account("zhang", manager);

        assertThatThrownBy(() -> service.delete(TENANT, manager.id()))
                .hasMessage("还有 1 个账号属于这个角色，先移走才能删除");
        assertThat(events).isEmpty();

        fixture.store.setUserRoles(zhang.value(), List.of(fixture.staffRole.id()));
        service.delete(TENANT, manager.id());

        assertThat(fixture.store.find(TENANT, manager.id())).isEmpty();
        assertThat(events).containsExactly(new RoleDeleted(TENANT, manager.id()));
    }

    @Test
    @DisplayName("补齐内置角色时按默认权限建，已有的不动")
    void ensureBuiltIns() {
        var fresh = new IdentityFixture();
        var other = new com.liming.rag.shared.TenantId(9);
        new RoleService(fresh.store, fresh.store, fresh.access, events::add).ensureBuiltIns(other);

        var created = fresh.store.list(other);
        assertThat(created).extracting(Role::builtIn)
                .containsExactly(Role.BuiltIn.SUPER_ADMIN, Role.BuiltIn.ADMIN, Role.BuiltIn.STAFF);
        assertThat(fresh.store.findBuiltIn(other, Role.BuiltIn.STAFF).orElseThrow().permissions())
                .containsExactly(Permissions.CHAT_USE);
        assertThat(fresh.store.list(TENANT)).hasSize(3);
    }
}
