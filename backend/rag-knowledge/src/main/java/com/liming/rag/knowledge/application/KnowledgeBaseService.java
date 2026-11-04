package com.liming.rag.knowledge.application;

import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.identity.api.UserDirectory.UserSummary;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.knowledge.application.KnowledgeQueries.DocumentRow;
import com.liming.rag.knowledge.application.KnowledgeQueries.ExpirySummary;
import com.liming.rag.knowledge.application.KnowledgeQueries.KnowledgeBaseSummary;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库与授权。能访问一个库 = 直接授权给本人，或授权给本人所属的某个角色（D-038）；授权的增减规则在
 * {@link KnowledgeBase} 聚合上。管理端也只能管理自己能访问的库（D-017），超级管理员例外：能看到并授权本租户全部的库，
 * 但检索同样按授权过滤。“能不能访问”的口径只在检索 SQL 的 {@code ChunkSearchMapper.accessibleKnowledgeBases} 一处，
 * 这里经 {@link KnowledgeQueries} 用它。
 */
@Service
public class KnowledgeBaseService implements KnowledgeAccess {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeQueries queries;
    private final UserDirectory users;
    private final DocumentCalendar calendar;
    private final KnowledgeSearch search;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, KnowledgeQueries queries,
            UserDirectory users, DocumentCalendar calendar, KnowledgeSearch search) {
        this.knowledgeBases = knowledgeBases;
        this.queries = queries;
        this.users = users;
        this.calendar = calendar;
        this.search = search;
    }

    /** 直接授权的账号：用户名和显示名一起给出。账号已被删除时只有 id。 */
    public record Member(long userId, String username, String displayName) {
    }

    /** 建库，建库的人自动获得直接授权。 */
    @Transactional
    public KnowledgeBaseRef create(TenantId tenantId, UserId creator, String name) {
        KnowledgeBase knowledgeBase;
        try {
            knowledgeBase = KnowledgeBase.create(tenantId, name, creator);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, e.getMessage());
        }
        if (knowledgeBases.existsByName(tenantId, knowledgeBase.name())) {
            throw nameTaken(knowledgeBase.name());
        }
        try {
            return ref(knowledgeBases.save(knowledgeBase));
        } catch (DuplicateKeyException e) {
            // 查重之后、插入之前别人建了同名的库：靠唯一约束兜底（#135）。
            throw nameTaken(knowledgeBase.name());
        }
    }

    /** 员工端选库用：授权给本人或其角色的库，按创建顺序。超级管理员也一样。 */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseRef> accessible(TenantId tenantId, UserId userId) {
        return queries.accessible(grantee(tenantId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> scope(TenantId tenantId, UserId userId, Collection<Long> requested) {
        var accessible = accessible(tenantId, userId).stream().map(KnowledgeBaseRef::id).toList();
        if (requested == null || requested.isEmpty()) {
            return accessible;
        }
        return requested.stream().filter(Objects::nonNull).distinct().filter(accessible::contains).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> names(TenantId tenantId, Collection<Long> knowledgeBaseIds) {
        return queries.names(tenantId, knowledgeBaseIds);
    }

    /**
     * 管理端列表（D-039）：按名称筛选、分页，带文档数、失败数、到期情况和授权情况。
     *
     * @param validity EXPIRING 或 EXPIRED（不区分大小写）时只列有这种文档的库，为空不筛
     */
    @Transactional(readOnly = true)
    public Paged<KnowledgeBaseSummary> page(TenantId tenantId, UserId actor, String keyword, String validity,
            int page, int size) {
        var state = parseValidity(validity);
        if (state == Validity.State.ACTIVE || state == Validity.State.PENDING) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "资料有效期只能按 EXPIRING 或 EXPIRED 筛选");
        }
        return queries.knowledgeBases(tenantId, visibleTo(tenantId, actor), blankToNull(keyword), state,
                calendar.window(), new Paged.Request(page, size));
    }

    /** 运营概览列出的到期资料份数上限。 */
    static final int EXPIRING_LIST_LIMIT = 20;

    /**
     * 运营概览的到期提醒（#124、#125）：自己能管理的库里即将到期和已失效的文档数，以及最早到期的
     * {@value #EXPIRING_LIST_LIMIT} 份。
     */
    @Transactional(readOnly = true)
    public ExpirySummary expirySummary(TenantId tenantId, UserId actor) {
        return queries.expirySummary(tenantId, visibleTo(tenantId, actor), calendar.window(), EXPIRING_LIST_LIMIT);
    }

    /** 超级管理员看本租户全部的库（null），其他人只看授权给自己或自己角色的。 */
    private Grantee visibleTo(TenantId tenantId, UserId actor) {
        return users.isSuperAdmin(tenantId, actor) ? null : grantee(tenantId, actor);
    }

    /** 按谁的授权算：本人和他现在所属的角色（由 identity 查，D-038）。 */
    private Grantee grantee(TenantId tenantId, UserId userId) {
        return new Grantee(tenantId, userId, users.roleIds(tenantId, userId));
    }

    /** 管理端的知识库详情：库不存在、或操作人不能管理它时一律“不存在”。 */
    @Transactional(readOnly = true)
    public KnowledgeBaseRef manageable(TenantId tenantId, UserId actor, long id) {
        return ref(getManageable(tenantId, actor, id));
    }

    /**
     * 管理操作的入口：库不存在、或操作人不能访问它时一律“不存在”，不暴露知识库是否存在。超级管理员能管理本租户全部的库，
     * 其他人按检索同一个口径判断能不能访问，只查这一个库。
     */
    KnowledgeBase getManageable(TenantId tenantId, UserId actor, long id) {
        if (!users.isSuperAdmin(tenantId, actor) && !queries.isAccessible(grantee(tenantId, actor), id)) {
            throw notFound();
        }
        return knowledgeBases.find(tenantId, id).orElseThrow(KnowledgeBaseService::notFound);
    }

    /**
     * @param status PARSING、READY、FAILED（不区分大小写），为空不筛
     * @param validity ACTIVE、EXPIRING、EXPIRED、PENDING（不区分大小写），为空不筛
     */
    @Transactional(readOnly = true)
    public Paged<DocumentRow> documents(TenantId tenantId, UserId actor, long knowledgeBaseId, String keyword,
            String status, String validity, int page, int size) {
        getManageable(tenantId, actor, knowledgeBaseId);
        return queries.documents(tenantId, knowledgeBaseId, blankToNull(keyword), parseStatus(status),
                parseValidity(validity), calendar.window(), new Paged.Request(page, size));
    }

    /**
     * 检索调试与评测用：在自己能管理的一个库里检索。命中的分块仍由检索服务按操作人的授权过滤（D-041），
     * 这里只保证库不存在、或操作人不能管理它时一律“不存在”。
     */
    public List<SearchHit> searchIn(TenantId tenantId, UserId actor, long knowledgeBaseId, String question,
            int limit) {
        getManageable(tenantId, actor, knowledgeBaseId);
        return search.search(new SearchQuery(tenantId, actor, List.of(knowledgeBaseId), question, limit));
    }

    @Transactional
    public void grant(TenantId tenantId, UserId actor, long knowledgeBaseId, UserId userId) {
        var knowledgeBase = getManageable(tenantId, actor, knowledgeBaseId);
        if (!users.exists(tenantId, userId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "账号不存在");
        }
        knowledgeBase.grant(userId);
        knowledgeBases.save(knowledgeBase);
    }

    /** 不能撤销自己，规则见 {@link KnowledgeBase#revoke}。 */
    @Transactional
    public void revoke(TenantId tenantId, UserId actor, long knowledgeBaseId, UserId userId) {
        var knowledgeBase = getManageable(tenantId, actor, knowledgeBaseId);
        try {
            knowledgeBase.revoke(actor, userId);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, e.getMessage());
        }
        knowledgeBases.save(knowledgeBase);
    }

    @Transactional(readOnly = true)
    public List<Member> members(TenantId tenantId, UserId actor, long knowledgeBaseId) {
        var ids = getManageable(tenantId, actor, knowledgeBaseId).members().stream().map(UserId::value).toList();
        var summaries = users.summaries(tenantId, ids);
        return ids.stream().map(id -> {
            var summary = summaries.get(id);
            return summary == null ? new Member(id, null, null)
                    : new Member(id, summary.username(), summary.displayName());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> roleGrants(TenantId tenantId, UserId actor, long knowledgeBaseId) {
        return List.copyOf(getManageable(tenantId, actor, knowledgeBaseId).roleGrants());
    }

    /** 授权给角色：属于这个角色的账号立即能检索到这个库。 */
    @Transactional
    public void grantRole(TenantId tenantId, UserId actor, long knowledgeBaseId, long roleId) {
        var knowledgeBase = getManageable(tenantId, actor, knowledgeBaseId);
        if (!users.roleExists(tenantId, roleId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "角色不存在");
        }
        knowledgeBase.grantRole(roleId);
        knowledgeBases.save(knowledgeBase);
    }

    /** 收回角色授权：属于这个角色、又没有其他途径获得授权的账号立即检索不到。 */
    @Transactional
    public void revokeRole(TenantId tenantId, UserId actor, long knowledgeBaseId, long roleId) {
        var knowledgeBase = getManageable(tenantId, actor, knowledgeBaseId);
        knowledgeBase.revokeRole(roleId);
        knowledgeBases.save(knowledgeBase);
    }

    /** 角色被删除时一并收回授权给它的库，在删除角色的事务里执行。 */
    @EventListener
    void on(RoleDeleted event) {
        knowledgeBases.removeGrantsOfRole(event.tenantId(), event.roleId());
    }

    private static Validity.State parseValidity(String validity) {
        if (validity == null || validity.isBlank()) {
            return null;
        }
        try {
            return Validity.State.valueOf(validity.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID,
                    "有效期只能是 ACTIVE、EXPIRING、EXPIRED 或 PENDING");
        }
    }

    private static DocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "状态只能是 PARSING、READY 或 FAILED");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static KnowledgeBaseRef ref(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseRef(knowledgeBase.id(), knowledgeBase.name());
    }

    private static KnowledgeException nameTaken(String name) {
        return new KnowledgeException(KnowledgeException.Reason.CONFLICT, "已有同名知识库：" + name);
    }

    private static KnowledgeException notFound() {
        return new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "知识库不存在");
    }
}
