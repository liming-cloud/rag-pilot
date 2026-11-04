package com.liming.rag.answering.application.ask;

import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchQuery;
import java.util.List;

/**
 * 检索步骤：调用 knowledge 的混合检索，把结果转成证据。权限过滤不在这里，在 knowledge 的检索 SQL 里（AGENTS.md）。
 */
final class EvidenceRetriever {

    private final KnowledgeSearch search;
    private final int candidates;

    EvidenceRetriever(KnowledgeSearch search, int candidates) {
        this.search = search;
        this.candidates = candidates;
    }

    List<Evidence> retrieve(SearchRequest request) {
        return search.search(new SearchQuery(request.tenantId(), request.userId(), request.knowledgeBaseIds(),
                request.text(), candidates)).stream()
                .map(Evidence::of)
                .toList();
    }
}
