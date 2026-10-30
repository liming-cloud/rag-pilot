package com.liming.rag.answering.application;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchQuery;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * 检索步骤：把 knowledge 模块的混合检索包装成 Spring AI 的 {@link DocumentRetriever}。
 *
 * <p>权限过滤不在这里，在 knowledge 的检索 SQL 里；这里只负责把提问人的身份原样交过去。
 * {@link Query#context()} 里缺少租户或提问人时直接拒绝，不会退化成不带身份的检索。
 */
final class KnowledgeDocumentRetriever implements DocumentRetriever {

    private final KnowledgeSearch search;
    private final int candidates;

    KnowledgeDocumentRetriever(KnowledgeSearch search, int candidates) {
        this.search = search;
        this.candidates = candidates;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Document> retrieve(Query query) {
        var context = query.context();
        if (!(context.get(AnswerContext.TENANT_ID) instanceof TenantId tenantId)
                || !(context.get(AnswerContext.USER_ID) instanceof UserId userId)) {
            throw new IllegalStateException("检索缺少提问人身份，拒绝执行");
        }
        var knowledgeBaseIds = (List<Long>) context.getOrDefault(AnswerContext.KNOWLEDGE_BASE_IDS, List.of());
        return search.search(new SearchQuery(tenantId, userId, knowledgeBaseIds, query.text(), candidates)).stream()
                .map(Evidence::of)
                .toList();
    }
}
