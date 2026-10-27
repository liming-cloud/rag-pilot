package com.liming.rag.knowledge.domain;

import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.util.List;
import java.util.Optional;

/** 知识库仓储。所有查询都带租户，防止跨租户读取。 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> find(TenantId tenantId, long id);

    /** 用户是成员的知识库，按创建顺序。 */
    List<KnowledgeBase> listAccessible(TenantId tenantId, UserId userId);

    void addMember(TenantId tenantId, long knowledgeBaseId, UserId userId);

    void removeMember(TenantId tenantId, long knowledgeBaseId, UserId userId);

    List<UserId> members(TenantId tenantId, long knowledgeBaseId);

    boolean existsByName(TenantId tenantId, String name);
}
