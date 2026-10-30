package com.liming.rag.knowledge.infrastructure.persistence;

import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.knowledge.application.KnowledgeQueries;
import com.liming.rag.knowledge.application.Paged;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.shared.TenantId;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
class MybatisKnowledgeQueries implements KnowledgeQueries {

    private final KnowledgeQueryMapper mapper;

    MybatisKnowledgeQueries(KnowledgeQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Paged<KnowledgeBaseSummary> knowledgeBases(TenantId tenantId, Grantee grantee, String keyword,
            Paged.Request page) {
        boolean all = grantee == null;
        Long userId = all ? null : grantee.userId().value();
        List<Long> roleIds = all ? List.of() : grantee.roleIds();
        var rows = mapper.knowledgeBases(tenantId.value(), all, userId, roleIds, keyword, page.offset(), page.size());
        long total = mapper.countKnowledgeBases(tenantId.value(), all, userId, roleIds, keyword);
        return new Paged<>(rows.stream().map(row -> new KnowledgeBaseSummary(number(row, "id"),
                (String) row.get("name"), number(row, "document_count"), number(row, "failed_count"),
                number(row, "role_grant_count"), number(row, "member_count"))).toList(),
                total, page.page(), page.size());
    }

    @Override
    public Paged<DocumentRow> documents(TenantId tenantId, long knowledgeBaseId, String keyword,
            DocumentStatus status, Paged.Request page) {
        var statusName = status == null ? null : status.name();
        var rows = mapper.documents(tenantId.value(), knowledgeBaseId, keyword, statusName, page.offset(),
                page.size());
        long total = mapper.countDocuments(tenantId.value(), knowledgeBaseId, keyword, statusName);
        return new Paged<>(rows.stream().map(row -> new DocumentRow(toDocument(row),
                (String) row.get("replaces_filename"))).toList(), total, page.page(), page.size());
    }

    private static Document toDocument(Map<String, Object> row) {
        var replaces = (Number) row.get("replaces_document_id");
        return Document.restore(number(row, "id"), new TenantId(number(row, "tenant_id")),
                number(row, "knowledge_base_id"), (String) row.get("filename"), (String) row.get("media_type"),
                ((String) row.get("sha256")).strip(), number(row, "size_bytes"),
                DocumentStatus.valueOf((String) row.get("status")), (String) row.get("error"),
                (int) number(row, "chunk_count"), timestamp(row.get("created_at")),
                replaces == null ? null : replaces.longValue());
    }

    /** 结果是 map 时 PostgreSQL 驱动把 timestamptz 读成 Timestamp。 */
    private static OffsetDateTime timestamp(Object value) {
        return value instanceof Timestamp timestamp
                ? OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault())
                : (OffsetDateTime) value;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
