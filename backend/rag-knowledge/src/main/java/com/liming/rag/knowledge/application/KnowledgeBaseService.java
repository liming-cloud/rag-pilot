package com.liming.rag.knowledge.application;

import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.identity.api.UserDirectory.UserSummary;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.application.KnowledgeQueries.DocumentRow;
import com.liming.rag.knowledge.application.KnowledgeQueries.KnowledgeBaseSummary;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库与授权。能访问一个库 = 直接授权给本人，或授权给本人所属的某个角色（D-038）；建库的人自动获得直接授权。
 * 管理端也只能管理自己能访问的库（D-017），超级管理员例外：能看到并授权本租户全部的库，但检索同样按授权过滤。
 * 检索时的授权过滤不在这里，在检索 SQL 里，见 {@code ChunkSearchMapper}。
 */
@Service
public class KnowledgeBaseService implements KnowledgeAccess {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeQueries queries;
    private final UserDirectory users;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, KnowledgeQueries queries,
            UserDirectory users) {
        this.knowledgeBases = knowledgeBases;
        this.queries = queries;
        this.users = users;
    }

    /** 直接授权的账号：用户名和显示名一起给出。账号已被删除时只有 id。 */
    public record Member(long userId, String username, String displayName) {
    }

    @Transactional
    public KnowledgeBase create(TenantId tenantId, UserId creator, String name) {
        KnowledgeBase knowledgeBase;
        try {
            knowledgeBase = KnowledgeBase.create(tenantId, name);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, e.getMessage());
        }
        if (knowledgeBases.existsByName(tenantId, knowledgeBase.name())) {
            throw new KnowledgeException(KnowledgeException.Reason.CONFLICT, "已有同名知识库：" + knowledgeBase.name());
        }
        var saved = knowledgeBases.save(knowledgeBase);
        knowledgeBases.addMember(tenantId, saved.id(), creator);
        return saved;
    }

    /** 员工端选库用：授权给本人或其角色的库。超级管理员也一样。 */
    @Transactional(readOnly = true)
    public List<KnowledgeBase> listAccessible(TenantId tenantId, UserId userId) {
        return knowledgeBases.listAccessible(tenantId, userId, users.roleIds(tenantId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseRef> accessible(TenantId tenantId, UserId userId) {
        return listAccessible(tenantId, userId).stream()
                .map(knowledgeBase -> new KnowledgeBaseRef(knowledgeBase.id(), knowledgeBase.name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> names(TenantId tenantId, Collection<Long> knowledgeBaseIds) {
        return knowledgeBases.findAll(tenantId, knowledgeBaseIds).stream()
                .collect(Collectors.toMap(KnowledgeBase::id, KnowledgeBase::name));
    }

    /** 管理端列表（D-039）：按名称筛选、分页，带文档数、失败数和授权情况。 */
    @Transactional(readOnly = true)
    public Paged<KnowledgeBaseSummary> page(TenantId tenantId, UserId actor, String keyword, int page, int size) {
        var grantee = users.isSuperAdmin(tenantId, actor) ? null
                : new Grantee(tenantId, actor, users.roleIds(tenantId, actor));
        return queries.knowledgeBases(tenantId, grantee, blankToNull(keyword), new Paged.Request(page, size));
    }

    /** 只要求知识库属于该租户；调用方已确认操作人能管理它（文档入库、后台处理）。 */
    @Transactional(readOnly = true)
    public KnowledgeBase get(TenantId tenantId, long id) {
        return knowledgeBases.find(tenantId, id).orElseThrow(KnowledgeBaseService::notFound);
    }

    /** 管理操作的入口：库不存在、或操作人不能访问它时一律“不存在”，不暴露知识库是否存在。 */
    @Transactional(readOnly = true)
    public KnowledgeBase getManageable(TenantId tenantId, UserId actor, long id) {
        if (users.isSuperAdmin(tenantId, actor)) {
            return knowledgeBases.find(tenantId, id).orElseThrow(KnowledgeBaseService::notFound);
        }
        return listAccessible(tenantId, actor).stream()
                .filter(knowledgeBase -> knowledgeBase.id() == id)
                .findFirst()
                .orElseThrow(KnowledgeBaseService::notFound);
    }

    /** @param status PARSING、READY、FAILED（不区分大小写），为空不筛 */
    @Transactional(readOnly = true)
    public Paged<DocumentRow> documents(TenantId tenantId, UserId actor, long knowledgeBaseId, String keyword,
            String status, int page, int size) {
        getManageable(tenantId, actor, knowledgeBaseId);
        return queries.documents(tenantId, knowledgeBaseId, blankToNull(keyword), parseStatus(status),
                new Paged.Request(page, size));
    }

    @Transactional
    public void grant(TenantId tenantId, UserId actor, long knowledgeBaseId, UserId userId) {
        getManageable(tenantId, actor, knowledgeBaseId);
        if (!users.exists(tenantId, userId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "账号不存在");
        }
        knowledgeBases.addMember(tenantId, knowledgeBaseId, userId);
    }

    /** 不能撤销自己：撤销后这个库可能从自己的管理界面上消失，也无法再授权回来。 */
    @Transactional
    public void revoke(TenantId tenantId, UserId actor, long knowledgeBaseId, UserId userId) {
        getManageable(tenantId, actor, knowledgeBaseId);
        if (userId.equals(actor)) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, "不能撤销自己的权限");
        }
        knowledgeBases.removeMember(tenantId, knowledgeBaseId, userId);
    }

    @Transactional(readOnly = true)
    public List<Member> members(TenantId tenantId, UserId actor, long knowledgeBaseId) {
        getManageable(tenantId, actor, knowledgeBaseId);
        var ids = knowledgeBases.members(tenantId, knowledgeBaseId).stream().map(UserId::value).toList();
        var summaries = users.summaries(tenantId, ids);
        return ids.stream().map(id -> {
            var summary = summaries.get(id);
            return summary == null ? new Member(id, null, null)
                    : new Member(id, summary.username(), summary.displayName());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> roleGrants(TenantId tenantId, UserId actor, long knowledgeBaseId) {
        getManageable(tenantId, actor, knowledgeBaseId);
        return knowledgeBases.roleGrants(tenantId, knowledgeBaseId);
    }

    /** 授权给角色：属于这个角色的账号立即能检索到这个库。 */
    @Transactional
    public void grantRole(TenantId tenantId, UserId actor, long knowledgeBaseId, long roleId) {
        getManageable(tenantId, actor, knowledgeBaseId);
        if (!users.roleExists(tenantId, roleId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "角色不存在");
        }
        knowledgeBases.addRoleGrant(tenantId, knowledgeBaseId, roleId);
    }

    /** 收回角色授权：属于这个角色、又没有其他途径获得授权的账号立即检索不到。 */
    @Transactional
    public void revokeRole(TenantId tenantId, UserId actor, long knowledgeBaseId, long roleId) {
        getManageable(tenantId, actor, knowledgeBaseId);
        knowledgeBases.removeRoleGrant(tenantId, knowledgeBaseId, roleId);
    }

    /** 角色被删除时一并收回授权给它的库，在删除角色的事务里执行。 */
    @EventListener
    void on(RoleDeleted event) {
        knowledgeBases.removeGrantsOfRole(event.tenantId(), event.roleId());
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

    private static KnowledgeException notFound() {
        return new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "知识库不存在");
    }
}
