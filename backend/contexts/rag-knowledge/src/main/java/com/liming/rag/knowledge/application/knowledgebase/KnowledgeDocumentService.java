package com.liming.rag.knowledge.application.knowledgebase;

import com.liming.rag.knowledge.api.DocumentRef;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeDocuments;
import com.liming.rag.knowledge.application.KnowledgeQueries;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.Texts;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档的只读查询（#224）：按员工列文档前先按 {@link KnowledgeAccess#scope} 确认能访问这个库。 */
@Service
class KnowledgeDocumentService implements KnowledgeDocuments {

    private final KnowledgeQueries queries;
    private final KnowledgeAccess access;

    KnowledgeDocumentService(KnowledgeQueries queries, KnowledgeAccess access) {
        this.queries = queries;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, DocumentRef> existing(TenantId tenantId, Collection<Long> documentIds) {
        return queries.documentRefs(tenantId, documentIds).stream()
                .collect(Collectors.toMap(DocumentRef::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentRef> ready(TenantId tenantId, UserId userId, long knowledgeBaseId, @Nullable String keyword,
            int limit) {
        if (!accessible(tenantId, userId, knowledgeBaseId)) {
            return List.of();
        }
        return queries.readyDocuments(tenantId, knowledgeBaseId, Texts.blankToNull(keyword), List.of(), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentRef> named(TenantId tenantId, UserId userId, long knowledgeBaseId,
            Collection<String> filenames) {
        if (filenames.isEmpty() || !accessible(tenantId, userId, knowledgeBaseId)) {
            return List.of();
        }
        return queries.readyDocuments(tenantId, knowledgeBaseId, null, filenames, filenames.size() * 2);
    }

    private boolean accessible(TenantId tenantId, UserId userId, long knowledgeBaseId) {
        return access.scope(tenantId, userId, List.of(knowledgeBaseId)).contains(knowledgeBaseId);
    }
}
