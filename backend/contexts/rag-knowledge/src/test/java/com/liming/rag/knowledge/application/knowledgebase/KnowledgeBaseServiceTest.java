package com.liming.rag.knowledge.application.knowledgebase;

import com.liming.rag.knowledge.application.Window;

import com.liming.rag.knowledge.application.InMemoryKnowledge;

import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.knowledge.application.KnowledgeQueries;
import com.liming.rag.knowledge.application.KnowledgeException;
import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.Paged;

import static com.liming.rag.knowledge.application.InMemoryKnowledge.OTHER_TENANT;
import static com.liming.rag.knowledge.application.InMemoryKnowledge.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** 知识库（D-038、D-041）：建库、可访问的库、管理范围、列表与检索调试；授权的增减见 {@link KnowledgeGrantServiceTest}。 */
class KnowledgeBaseServiceTest {

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
    @DisplayName("建库：名称去掉首尾空格，建库的人自动获得直接授权")
    void createGrantsCreator() {
        var created = service.create(TENANT, ADMIN, "  门店规范  ");

        assertThat(created.name()).isEqualTo("门店规范");
        assertThat(fixture.knowledgeBases.rows.get(created.id()).members()).containsExactly(ADMIN.value());
    }

    @Test
    @DisplayName("建库：名称为空或太长是 INVALID，同名是 CONFLICT，并发撞上唯一约束也是 CONFLICT，都不留授权")
    void rejectsBadOrDuplicateNames() {
        service.create(TENANT, ADMIN, "门店规范");

        assertReason(() -> service.create(TENANT, ADMIN, " "), KnowledgeException.Reason.INVALID);
        assertReason(() -> service.create(TENANT, ADMIN, "长".repeat(KnowledgeBase.MAX_NAME_LENGTH + 1)),
                KnowledgeException.Reason.INVALID);
        assertReason(() -> service.create(TENANT, ADMIN, "门店规范"), KnowledgeException.Reason.CONFLICT);
        fixture.knowledgeBases.failNextSave = new DuplicateKeyException("knowledge_base_name_unique");
        assertReason(() -> service.create(TENANT, ADMIN, "并发同名"), KnowledgeException.Reason.CONFLICT);

        assertThat(fixture.knowledgeBases.rows).hasSize(1);
    }

    @Test
    @DisplayName("可访问的库 = 直接授权给本人的 ∪ 授权给其角色的；超级管理员在员工端也只看到授权给自己的")
    void listsAccessibleByUserOrRole() {
        var direct = service.create(TENANT, ADMIN, "直接授权库");
        var byRole = service.create(TENANT, ADMIN, "角色授权库");
        service.create(TENANT, ADMIN, "未授权库");
        grants.grant(TENANT, ADMIN, direct.id(), STAFF);
        grants.grantRole(TENANT, ADMIN, byRole.id(), STORE_ROLE);

        assertThat(service.accessible(TENANT, STAFF)).containsExactly(
                new KnowledgeBaseRef(direct.id(), "直接授权库"), new KnowledgeBaseRef(byRole.id(), "角色授权库"));
        assertThat(service.accessible(TENANT, SUPER)).isEmpty();
        assertThat(service.names(TENANT, List.of(direct.id(), 999L))).containsOnlyKeys(direct.id());
    }

    @Test
    @DisplayName("实际检索的范围：点名的库里有权访问的（保持顺序、去重）；没点名时是全部有权访问的库")
    void resolvesScope() {
        var store = service.create(TENANT, ADMIN, "门店规范").id();
        var goods = service.create(TENANT, ADMIN, "商品知识").id();
        var manager = service.create(TENANT, ADMIN, "店长手册").id();
        grants.grant(TENANT, ADMIN, store, STAFF);
        grants.grantRole(TENANT, ADMIN, goods, STORE_ROLE);

        assertThat(service.scope(TENANT, STAFF, null)).containsExactly(store, goods);
        assertThat(service.scope(TENANT, STAFF, List.of())).containsExactly(store, goods);
        assertThat(service.scope(TENANT, STAFF, List.of(goods, manager, goods, store))).containsExactly(goods, store);
        assertThat(service.scope(TENANT, STAFF, List.of(manager))).isEmpty();
        assertThat(service.scope(TENANT, OUTSIDER, null)).isEmpty();
    }

    @Test
    @DisplayName("管理范围：只能管理自己能访问的库，超级管理员能管理本租户全部的库；别的租户的库一律不存在")
    void managesOnlyAccessibleKnowledgeBases() {
        var kb = service.create(TENANT, ADMIN, "门店规范");

        assertThat(service.getManageable(TENANT, ADMIN, kb.id()).id()).isEqualTo(kb.id());
        assertThat(service.getManageable(TENANT, SUPER, kb.id()).id()).isEqualTo(kb.id());
        assertReason(() -> service.getManageable(TENANT, OUTSIDER, kb.id()), KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> service.getManageable(OTHER_TENANT, SUPER, kb.id()), KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> service.getManageable(TENANT, SUPER, 999), KnowledgeException.Reason.NOT_FOUND);
    }

    @Test
    @DisplayName("管理端列表：超级管理员看全部（不带授权范围），其他人按本人和角色过滤；有效期只能按 EXPIRING、EXPIRED 筛")
    void pagesByVisibility() {
        service.page(TENANT, SUPER, " 门店 ", "expiring", 2, 10);
        assertThat(fixture.queries.superAdminView).isTrue();
        assertThat(fixture.queries.keyword).isEqualTo("门店");
        assertThat(fixture.queries.validity).isEqualTo(Validity.State.EXPIRING);
        assertThat(fixture.queries.page).isEqualTo(new Paged.Request(2, 10));

        service.page(TENANT, STAFF, "  ", null, 1, 20);
        assertThat(fixture.queries.grantee).isEqualTo(new Grantee(TENANT, STAFF, List.of(STORE_ROLE)));
        assertThat(fixture.queries.keyword).isNull();
        assertThat(fixture.queries.window).isEqualTo(new Window(InMemoryKnowledge.TODAY,
                InMemoryKnowledge.TODAY.plusDays(30)));

        assertReason(() -> service.page(TENANT, ADMIN, null, "ACTIVE", 1, 20), KnowledgeException.Reason.INVALID);
        assertReason(() -> service.page(TENANT, ADMIN, null, "SOON", 1, 20), KnowledgeException.Reason.INVALID);
    }

    @Test
    @DisplayName("文档列表：要能管理这个库；状态、有效期不区分大小写，不认识的是 INVALID")
    void listsDocumentsOfManageableKnowledgeBase() {
        var kb = service.create(TENANT, ADMIN, "门店规范");

        service.documents(TENANT, ADMIN, kb.id(), new KnowledgeBaseService.DocumentFilter(null, "ready", "pending", 1, 20));
        assertThat(fixture.queries.status).isEqualTo(DocumentStatus.READY);
        assertThat(fixture.queries.validity).isEqualTo(Validity.State.PENDING);

        assertReason(() -> service.documents(TENANT, OUTSIDER, kb.id(), new KnowledgeBaseService.DocumentFilter(null, null, null, 1, 20)),
                KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> service.documents(TENANT, ADMIN, kb.id(), new KnowledgeBaseService.DocumentFilter(null, "DONE", null, 1, 20)),
                KnowledgeException.Reason.INVALID);
    }

    @Test
    @DisplayName("运营概览的到期提醒按管理范围统计，最多列 20 份")
    void summarizesExpiryWithinVisibility() {
        service.expirySummary(TENANT, STAFF);

        assertThat(fixture.queries.grantee).isEqualTo(new Grantee(TENANT, STAFF, List.of(STORE_ROLE)));
        assertThat(fixture.queries.limit).isEqualTo(KnowledgeBaseService.EXPIRING_LIST_LIMIT);
    }

    @Test
    @DisplayName("检索调试：只在能管理的库里检索，检索请求带上操作人和这个库")
    void searchesWithinManageableKnowledgeBase() {
        var kb = service.create(TENANT, ADMIN, "门店规范");

        assertThat(service.searchIn(TENANT, ADMIN, kb.id(), "交班", 5)).hasSize(1);
        assertThat(searched).containsExactly(new SearchQuery(TENANT, ADMIN, List.of(kb.id()), "交班", 5));
        assertReason(() -> service.searchIn(TENANT, OUTSIDER, kb.id(), "交班", 5), KnowledgeException.Reason.NOT_FOUND);
        assertThat(searched).hasSize(1);
    }

    /** 按错误码断言：规则违规可能来自 application 的 KnowledgeException，也可能来自领域对象（D-067），错误码是同一套。 */
    private static void assertReason(ThrowingCallable call, KnowledgeException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("KNOWLEDGE_" + reason.name()));
    }
}
