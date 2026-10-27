package com.liming.rag.knowledge.application;

import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库与成员。员工只能看到自己是成员的知识库；建库的人自动成为成员。
 * 检索时的成员过滤不在这里，在检索 SQL 里，见 {@code ChunkSearchMapper}。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final UserDirectory users;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, UserDirectory users) {
        this.knowledgeBases = knowledgeBases;
        this.users = users;
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

    @Transactional(readOnly = true)
    public List<KnowledgeBase> listAccessible(TenantId tenantId, UserId userId) {
        return knowledgeBases.listAccessible(tenantId, userId);
    }

    /** 管理操作用：只要求知识库属于该租户。 */
    @Transactional(readOnly = true)
    public KnowledgeBase get(TenantId tenantId, long id) {
        return knowledgeBases.find(tenantId, id).orElseThrow(KnowledgeBaseService::notFound);
    }

    /** 员工读取用：不是成员时和不存在一样返回“不存在”，不暴露知识库是否存在。 */
    @Transactional(readOnly = true)
    public KnowledgeBase getAccessible(TenantId tenantId, UserId userId, long id) {
        return knowledgeBases.listAccessible(tenantId, userId).stream()
                .filter(knowledgeBase -> knowledgeBase.id() == id)
                .findFirst()
                .orElseThrow(KnowledgeBaseService::notFound);
    }

    @Transactional
    public void grant(TenantId tenantId, long knowledgeBaseId, UserId userId) {
        get(tenantId, knowledgeBaseId);
        if (!users.exists(tenantId, userId)) {
            throw new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "账号不存在");
        }
        knowledgeBases.addMember(tenantId, knowledgeBaseId, userId);
    }

    @Transactional
    public void revoke(TenantId tenantId, long knowledgeBaseId, UserId userId) {
        get(tenantId, knowledgeBaseId);
        knowledgeBases.removeMember(tenantId, knowledgeBaseId, userId);
    }

    @Transactional(readOnly = true)
    public List<UserId> members(TenantId tenantId, long knowledgeBaseId) {
        get(tenantId, knowledgeBaseId);
        return knowledgeBases.members(tenantId, knowledgeBaseId);
    }

    private static KnowledgeException notFound() {
        return new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "知识库不存在");
    }
}
