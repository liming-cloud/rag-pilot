package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 知识库聚合的授权规则（D-038、#136）。 */
class KnowledgeBaseTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId ADMIN = new UserId(1);
    private static final UserId STAFF = new UserId(2);

    @Test
    @DisplayName("建库：名称去掉首尾空格，1 到 100 个字符；建库的人自动获得直接授权，作为待写入的变化")
    void createGrantsCreator() {
        var created = KnowledgeBase.create(TENANT, "  门店规范  ", ADMIN);

        assertThat(created.name()).isEqualTo("门店规范");
        assertThat(created.members()).containsExactly(ADMIN);
        assertThat(created.addedMembers()).containsExactly(ADMIN);
        assertThatThrownBy(() -> KnowledgeBase.create(TENANT, " ", ADMIN)).isInstanceOf(KnowledgeRuleException.class);
        assertThatThrownBy(() -> KnowledgeBase.create(TENANT, "长".repeat(101), ADMIN))
                .isInstanceOf(KnowledgeRuleException.class);
    }

    @Test
    @DisplayName("授权和撤销只记真正的变化：重复授权、撤销没有的授权不算；加了又撤销等于没变")
    void tracksOnlyRealChanges() {
        var kb = KnowledgeBase.restore(10, TENANT, "门店规范", List.of(ADMIN), List.of(40L));

        kb.grant(ADMIN);
        kb.revoke(ADMIN, new UserId(99));
        kb.grantRole(40);
        assertThat(kb.addedMembers()).isEmpty();
        assertThat(kb.removedMembers()).isEmpty();
        assertThat(kb.addedRoles()).isEmpty();

        kb.grant(STAFF);
        kb.revoke(ADMIN, STAFF);
        kb.revokeRole(40);
        kb.grantRole(40);
        assertThat(kb.addedMembers()).isEmpty();
        assertThat(kb.removedMembers()).isEmpty();
        assertThat(kb.addedRoles()).isEmpty();
        assertThat(kb.removedRoles()).isEmpty();

        kb.grant(STAFF);
        kb.revokeRole(40);
        assertThat(kb.members()).containsExactly(ADMIN, STAFF);
        assertThat(kb.addedMembers()).containsExactly(STAFF);
        assertThat(kb.roleGrants()).isEmpty();
        assertThat(kb.removedRoles()).containsExactly(40L);
    }

    @Test
    @DisplayName("不能撤销自己的授权")
    void cannotRevokeSelf() {
        var kb = KnowledgeBase.restore(10, TENANT, "门店规范", List.of(ADMIN, STAFF), List.of());

        assertThatThrownBy(() -> kb.revoke(ADMIN, ADMIN)).isInstanceOf(KnowledgeRuleException.class)
                .hasMessage("不能撤销自己的权限");
        kb.revoke(ADMIN, STAFF);

        assertThat(kb.members()).containsExactly(ADMIN);
        assertThat(kb.removedMembers()).containsExactly(STAFF);
    }
}
