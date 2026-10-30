package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Map;

/** 分块检索端口，实现里的每条召回 SQL 都必须带租户和成员过滤；knowledgeBaseIds 为空表示不再缩小范围。 */
public interface ChunkIndex {

    List<Long> nearest(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, float[] vector, int limit);

    List<Long> keyword(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String text, int limit);

    /** 按给定顺序返回分块详情，分数先填 0，由调用方补上。 */
    List<SearchHit> load(TenantId tenantId, List<Long> chunkIds);

    /** 只取员工是成员的知识库里的分块原文：分块 id → 原文。 */
    Map<Long, String> accessibleContents(TenantId tenantId, UserId userId, List<Long> chunkIds);
}
