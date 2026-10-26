package com.liming.rag.knowledge.domain;

import com.liming.rag.common.TenantId;
import java.util.List;
import java.util.Optional;

/** 知识库仓储。所有查询都带租户，防止跨租户读取。 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> find(TenantId tenantId, long id);

    List<KnowledgeBase> list(TenantId tenantId);

    boolean existsByName(TenantId tenantId, String name);
}
