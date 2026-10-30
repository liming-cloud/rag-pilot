package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 知识库仓储。所有查询都带租户，防止跨租户读取。 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> find(TenantId tenantId, long id);

    /** 用户是成员的知识库，按创建顺序。 */
    List<KnowledgeBase> listAccessible(TenantId tenantId, UserId userId);

    /** 按 id 取本租户的知识库，不检查成员关系；不存在的 id 不返回。 */
    List<KnowledgeBase> findAll(TenantId tenantId, Collection<Long> ids);

    void addMember(TenantId tenantId, long knowledgeBaseId, UserId userId);

    void removeMember(TenantId tenantId, long knowledgeBaseId, UserId userId);

    List<UserId> members(TenantId tenantId, long knowledgeBaseId);

    boolean existsByName(TenantId tenantId, String name);
}
