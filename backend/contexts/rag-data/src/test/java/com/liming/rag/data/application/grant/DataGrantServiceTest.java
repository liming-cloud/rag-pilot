package com.liming.rag.data.application.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.data.application.DataException;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.Fakes;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.GranteeType;
import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataGrantServiceTest {

    private static final UserId ADMIN = new UserId(9);

    private final Fakes.MemoryGrants store = new Fakes.MemoryGrants();
    private final Fakes.Audit audit = new Fakes.Audit();
    private final DataGrantService service = new DataGrantService(store, new Fakes.Directory(),
            new Fakes.Master().master(), new DataProperties("retail", "list_stores",
                    List.of(new DataProperties.Tool("get_inventory", "库存查询"))), audit);

    @Test
    @DisplayName("授权：保存并记一条审计；再收回：授权消失并记一条收回的审计")
    void grantThenRevokeAreAudited() {
        service.replace(Fakes.TENANT, ADMIN, "user", 1, List.of("get_inventory"), List.of("S1"), List.of("east"));

        assertThat(store.find(Fakes.TENANT, GranteeType.USER, 1)).isEqualTo(
                new Grants(java.util.Set.of("get_inventory"), java.util.Set.of("S1"), java.util.Set.of("east")));
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0)).startsWith("DATA_GRANTED|DATA_GRANT|USER:1|").contains("S1").contains("east");

        service.replace(Fakes.TENANT, ADMIN, "USER", 1, List.of("get_inventory"), List.of(), List.of());

        assertThat(store.find(Fakes.TENANT, GranteeType.USER, 1).units()).isEmpty();
        assertThat(audit.entries).hasSize(2);
        assertThat(audit.entries.get(1)).startsWith("DATA_REVOKED|DATA_GRANT|USER:1|").contains("S1").contains("east");
    }

    @Test
    @DisplayName("没有变化不记审计")
    void unchangedWritesNoAudit() {
        service.replace(Fakes.TENANT, ADMIN, "ROLE", 100, List.of(), List.of("S1"), List.of());
        audit.entries.clear();

        service.replace(Fakes.TENANT, ADMIN, "ROLE", 100, List.of(), List.of("S1"), List.of());

        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("不存在的账号、不认识的对象类型、不在清单里的工具、空编号：拒绝且不写入、不记审计")
    void invalidRequestsWriteNothing() {
        assertThatThrownBy(() -> service.replace(Fakes.TENANT, ADMIN, "USER", 99, List.of(), List.of("S1"), List.of()))
                .isInstanceOf(DataException.class).hasMessage("账号不存在");
        assertThatThrownBy(() -> service.replace(Fakes.TENANT, ADMIN, "TEAM", 1, List.of(), List.of("S1"), List.of()))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> service.replace(Fakes.TENANT, ADMIN, "USER", 1, List.of("drop_all"), List.of(), List.of()))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> service.replace(Fakes.TENANT, ADMIN, "USER", 1, List.of(), List.of(" "), List.of()))
                .isInstanceOf(DataException.class);

        assertThat(store.find(Fakes.TENANT, GranteeType.USER, 1)).isEqualTo(Grants.none());
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("角色被删除：授权给它的内容一并收回")
    void roleDeletionRevokesItsGrants() {
        service.replace(Fakes.TENANT, ADMIN, "ROLE", 100, List.of("get_inventory"), List.of("S1"), List.of());

        service.on(new RoleDeleted(Fakes.TENANT, 100));

        assertThat(store.find(Fakes.TENANT, GranteeType.ROLE, 100)).isEqualTo(Grants.none());
    }
}
