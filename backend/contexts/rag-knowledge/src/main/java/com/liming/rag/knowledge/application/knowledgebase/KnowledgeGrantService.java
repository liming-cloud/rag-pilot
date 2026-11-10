package com.liming.rag.knowledge.application.knowledgebase;

import com.liming.rag.knowledge.application.KnowledgeException;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库授权（D-038、D-041）：授权给账号或角色、撤销、列出授权。只能授权自己能管理的库（经
 * {@link KnowledgeBaseService#getManageable}），授权的增减规则在 {@link KnowledgeBase} 聚合上；成功的授权变更记审计（#145 C2）。
 */
@Service
public class KnowledgeGrantService {

    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBaseRepository repository;
    private final UserDirectory users;
    private final AuditTrail audit;

    public KnowledgeGrantService(KnowledgeBaseService knowledgeBases, KnowledgeBaseRepository repository,
            UserDirectory users, AuditTrail audit) {
        this.knowledgeBases = knowledgeBases;
        this.repository = repository;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public void grant(TenantId tenantId, UserId actor, long knowledgeBaseId, UserId userId) {
        var knowledgeBase = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        if (!users.exists(tenantId, userId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "账号不存在");
        }
        knowledgeBase.grant(userId);
        repository.save(knowledgeBase);
        audit(tenantId, actor, "KB_MEMBER_GRANTED", knowledgeBase, "知识库“" + knowledgeBase.name() + "”授权给账号 "
                + username(tenantId, userId));
    }

    /** 不能撤销自己，规则见 {@link KnowledgeBase#revoke}。 */
    @Transactional
    public void revoke(TenantId tenantId, UserId actor, long knowledgeBaseId, UserId userId) {
        var knowledgeBase = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        knowledgeBase.revoke(actor, userId);
        repository.save(knowledgeBase);
        audit(tenantId, actor, "KB_MEMBER_REVOKED", knowledgeBase, "知识库“" + knowledgeBase.name() + "”撤销账号 "
                + username(tenantId, userId) + " 的授权");
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseMember> members(TenantId tenantId, UserId actor, long knowledgeBaseId) {
        var ids = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId).members().stream().map(UserId::value).toList();
        var summaries = users.summaries(tenantId, ids);
        return ids.stream().map(id -> {
            var summary = summaries.get(id);
            return summary == null ? new KnowledgeBaseMember(id, null, null)
                    : new KnowledgeBaseMember(id, summary.username(), summary.displayName());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> roleGrants(TenantId tenantId, UserId actor, long knowledgeBaseId) {
        return List.copyOf(knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId).roleGrants());
    }

    /** 授权给角色：属于这个角色的账号立即能检索到这个库。 */
    @Transactional
    public void grantRole(TenantId tenantId, UserId actor, long knowledgeBaseId, long roleId) {
        var knowledgeBase = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        if (!users.roleExists(tenantId, roleId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "角色不存在");
        }
        knowledgeBase.grantRole(roleId);
        repository.save(knowledgeBase);
        audit(tenantId, actor, "KB_ROLE_GRANTED", knowledgeBase, "知识库“" + knowledgeBase.name() + "”授权给角色 #" + roleId);
    }

    /** 收回角色授权：属于这个角色、又没有其他途径获得授权的账号立即检索不到。 */
    @Transactional
    public void revokeRole(TenantId tenantId, UserId actor, long knowledgeBaseId, long roleId) {
        var knowledgeBase = knowledgeBases.getManageable(tenantId, actor, knowledgeBaseId);
        knowledgeBase.revokeRole(roleId);
        repository.save(knowledgeBase);
        audit(tenantId, actor, "KB_ROLE_REVOKED", knowledgeBase, "知识库“" + knowledgeBase.name() + "”收回角色 #" + roleId
                + " 的授权");
    }

    private void audit(TenantId tenantId, UserId actor, String action, KnowledgeBase knowledgeBase, String detail) {
        audit.record(tenantId, actor, action, "KNOWLEDGE_BASE", String.valueOf(knowledgeBase.id()), detail);
    }

    private String username(TenantId tenantId, UserId userId) {
        var summary = users.summaries(tenantId, List.of(userId.value())).get(userId.value());
        return summary == null ? "#" + userId.value() : summary.username();
    }

    /** 角色被删除时一并收回授权给它的库，在删除角色的事务里执行。 */
    @EventListener
    void on(RoleDeleted event) {
        repository.removeGrantsOfRole(event.tenantId(), event.roleId());
    }
}
