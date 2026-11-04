package com.liming.rag.knowledge.application;

import static com.liming.rag.knowledge.application.InMemoryKnowledge.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 知识库授权（D-038、D-041、#145 C2）：授权给账号和角色、撤销、角色删除时一并收回，成功的变更记审计。 */
class KnowledgeGrantServiceTest {

    private static final UserId ADMIN = new UserId(1);
    private static final UserId STAFF = new UserId(2);
    private static final UserId OUTSIDER = new UserId(3);
    private static final UserId SUPER = new UserId(9);
    private static final long STORE_ROLE = 40;

    private final InMemoryKnowledge fixture = new InMemoryKnowledge();
    private final List<SearchQuery> searched = new ArrayList<>();
    private final KnowledgeBaseService service = new KnowledgeBaseService(fixture.knowledgeBases, fixture.queries,
            fixture.users, fixture.calendar, query -> {
                searched.add(query);
                return List.of(new SearchHit(1, 2, "doc", "doc", "text", 0.5));
            });
    private final KnowledgeGrantService grants = new KnowledgeGrantService(service, fixture.knowledgeBases,
            fixture.users, fixture.audit);

    {
        fixture.users.user(1, "管理员").user(2, "店员", STORE_ROLE).user(3, "外人").user(9, "超级管理员");
        fixture.users.superAdmins.add(9L);
        fixture.users.existingRoles.add(STORE_ROLE);
    }

    @Test
    @DisplayName("授权给账号：账号要存在；不能管理这个库的人授权按库不存在处理，什么都不写")
    void grantsToExistingAccountsOnly() {
        var kb = service.create(TENANT, ADMIN, "门店规范");

        assertReason(() -> grants.grant(TENANT, ADMIN, kb.id(), new UserId(404)), KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> grants.grant(TENANT, OUTSIDER, kb.id(), OUTSIDER), KnowledgeException.Reason.NOT_FOUND);
        grants.grant(TENANT, ADMIN, kb.id(), STAFF);

        assertThat(grants.members(TENANT, ADMIN, kb.id())).extracting(KnowledgeGrantService.Member::userId)
                .containsExactly(ADMIN.value(), STAFF.value());
        assertThat(grants.members(TENANT, ADMIN, kb.id())).extracting(KnowledgeGrantService.Member::displayName)
                .containsExactly("管理员", "店员");
        assertThat(fixture.audited).as("只有成功的授权记审计（#145 C2）").singleElement()
                .satisfies(entry -> assertThat(entry).startsWith("KB_MEMBER_GRANTED 知识库“门店规范”授权给账号 "));
    }

    @Test
    @DisplayName("撤销授权：不能撤销自己；撤销别人后对方立即访问不到")
    void revokesOthersButNotSelf() {
        var kb = service.create(TENANT, ADMIN, "门店规范");
        grants.grant(TENANT, ADMIN, kb.id(), STAFF);

        assertReason(() -> grants.revoke(TENANT, ADMIN, kb.id(), ADMIN), KnowledgeException.Reason.INVALID);
        grants.revoke(TENANT, ADMIN, kb.id(), STAFF);

        assertThat(service.accessible(TENANT, STAFF)).isEmpty();
        assertThat(service.accessible(TENANT, ADMIN)).extracting(KnowledgeBaseRef::id).containsExactly(kb.id());
        assertThat(fixture.audited).extracting(entry -> entry.split(" ")[0])
                .as("撤销自己失败不记").containsExactly("KB_MEMBER_GRANTED", "KB_MEMBER_REVOKED");
    }

    @Test
    @DisplayName("授权给角色：角色要存在；收回后属于这个角色的账号访问不到；角色删除时一并收回")
    void grantsAndRevokesRoles() {
        var kb = service.create(TENANT, ADMIN, "门店规范");

        assertReason(() -> grants.grantRole(TENANT, ADMIN, kb.id(), 404), KnowledgeException.Reason.NOT_FOUND);
        grants.grantRole(TENANT, ADMIN, kb.id(), STORE_ROLE);
        assertThat(grants.roleGrants(TENANT, ADMIN, kb.id())).containsExactly(STORE_ROLE);
        assertThat(service.accessible(TENANT, STAFF)).hasSize(1);

        grants.revokeRole(TENANT, ADMIN, kb.id(), STORE_ROLE);
        assertThat(service.accessible(TENANT, STAFF)).isEmpty();

        grants.grantRole(TENANT, ADMIN, kb.id(), STORE_ROLE);
        grants.on(new RoleDeleted(TENANT, STORE_ROLE));
        assertThat(grants.roleGrants(TENANT, ADMIN, kb.id())).isEmpty();
        assertThat(fixture.audited).as("角色删除时的收回由删除角色那条审计记录覆盖").containsExactly(
                "KB_ROLE_GRANTED 知识库“门店规范”授权给角色 #" + STORE_ROLE,
                "KB_ROLE_REVOKED 知识库“门店规范”收回角色 #" + STORE_ROLE + " 的授权",
                "KB_ROLE_GRANTED 知识库“门店规范”授权给角色 #" + STORE_ROLE);
    }

    private static void assertReason(ThrowingCallable call, KnowledgeException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(KnowledgeException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
