package com.liming.rag.evaluation.application;

import com.liming.rag.knowledge.api.DocumentRef;
import com.liming.rag.knowledge.api.KnowledgeDocuments;
import com.liming.rag.shared.TenantId;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 期望文档现在的情况：还在（带当前文件名）或已删除；有期望文档已删除的题是“过期”题，不参与运行（D-070）。 */
@Component
public class DocumentStates {

    /** 还在的期望文档。 */
    public record Existing(Map<Long, DocumentRef> documents) {

        public boolean deleted(long documentId) {
            return !documents.containsKey(documentId);
        }

        public boolean stale(GoldenQuestion question) {
            return question.expected().stream().anyMatch(document -> deleted(document.documentId()));
        }

        /** 当前文件名；文档已删除时用保存时的文件名。 */
        public String filename(ExpectedDocument document) {
            var current = documents.get(document.documentId());
            return current == null ? document.filename() : current.filename();
        }
    }

    private final KnowledgeDocuments documents;

    DocumentStates(KnowledgeDocuments documents) {
        this.documents = documents;
    }

    public Existing of(TenantId tenantId, Collection<GoldenQuestion> questions) {
        var ids = questions.stream().flatMap(question -> question.expected().stream())
                .map(ExpectedDocument::documentId).distinct().toList();
        return new Existing(documents.existing(tenantId, ids));
    }
}
