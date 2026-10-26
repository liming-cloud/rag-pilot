package com.liming.rag.knowledge.api;

import java.util.List;

/**
 * 知识检索：向量召回与 BM25 关键词召回各取若干条，用倒数排名融合合并。
 *
 * <p>权限过滤只在这里做一次：SQL 里限定租户和 {@link SearchQuery#knowledgeBaseIds()}，
 * 其他模块不得绕过本接口直接查分块。
 */
public interface KnowledgeSearch {

    List<SearchHit> search(SearchQuery query);
}
