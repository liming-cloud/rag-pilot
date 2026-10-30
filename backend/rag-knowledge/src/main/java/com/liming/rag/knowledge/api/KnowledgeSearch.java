package com.liming.rag.knowledge.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 知识检索：向量召回与 BM25 关键词召回各取若干条，用倒数排名融合合并。
 *
 * <p>权限过滤只在这里做一次：SQL 里限定租户和 {@link SearchQuery#knowledgeBaseIds()}，
 * 其他模块不得绕过本接口直接查分块。
 */
public interface KnowledgeSearch {

    List<SearchHit> search(SearchQuery query);

    /**
     * 按分块取原文，用于历史回答的“查看引用原文”。和召回一样只返回员工是成员的知识库里的分块：
     * 授权被收回、文档被删的分块不返回，调用方据此显示“已无权查看原文”（D-036）。默认实现一条也不返回。
     *
     * @return 分块 id → 原文
     */
    default Map<Long, String> excerpts(TenantId tenantId, UserId userId, Collection<Long> chunkIds) {
        return Map.of();
    }
}
