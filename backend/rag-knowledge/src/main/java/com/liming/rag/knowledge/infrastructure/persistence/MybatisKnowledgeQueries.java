package com.liming.rag.knowledge.infrastructure.persistence;

import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.knowledge.application.KnowledgeQueries;
import com.liming.rag.knowledge.application.Paged;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
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
            Validity.State validity, Window window, Paged.Request page) {
        boolean all = grantee == null;
        Long userId = all ? null : grantee.userId().value();
        List<Long> roleIds = all ? List.of() : grantee.roleIds();
        var state = validity == null ? null : validity.name();
        var rows = mapper.knowledgeBases(tenantId.value(), all, userId, roleIds, keyword, state, window.today(),
                window.warnUntil(), page.offset(), page.size());
        long total = mapper.countKnowledgeBases(tenantId.value(), all, userId, roleIds, keyword, state,
                window.today(), window.warnUntil());
        return new Paged<>(rows.stream().map(row -> new KnowledgeBaseSummary(number(row, "id"),
                (String) row.get("name"), number(row, "document_count"), number(row, "failed_count"),
                number(row, "expiring_count"), number(row, "expired_count"), number(row, "role_grant_count"),
                number(row, "member_count"))).toList(), total, page.page(), page.size());
    }

    @Override
    public Paged<DocumentRow> documents(TenantId tenantId, long knowledgeBaseId, String keyword,
            DocumentStatus status, Validity.State validity, Window window, Paged.Request page) {
        var statusName = status == null ? null : status.name();
        var state = validity == null ? null : validity.name();
        var rows = mapper.documents(tenantId.value(), knowledgeBaseId, keyword, statusName, state, window.today(),
                window.warnUntil(), page.offset(), page.size());
        long total = mapper.countDocuments(tenantId.value(), knowledgeBaseId, keyword, statusName, state,
                window.today(), window.warnUntil());
        return new Paged<>(rows.stream().map(row -> new DocumentRow(toDocument(row),
                (String) row.get("replaces_filename"))).toList(), total, page.page(), page.size());
    }

    @Override
    public ExpirySummary expirySummary(TenantId tenantId, Grantee grantee, Window window, int limit) {
        boolean all = grantee == null;
        Long userId = all ? null : grantee.userId().value();
        List<Long> roleIds = all ? List.of() : grantee.roleIds();
        var row = mapper.expirySummary(tenantId.value(), all, userId, roleIds, window.today(), window.warnUntil());
        var documents = mapper.expiringDocuments(tenantId.value(), all, userId, roleIds, window.today(),
                window.warnUntil(), limit).stream()
                .map(document -> new ExpiringDocument(number(document, "knowledge_base_id"),
                        (String) document.get("knowledge_base_name"), number(document, "id"),
                        (String) document.get("filename"), date(document.get("expires_on")),
                        Validity.State.valueOf((String) document.get("validity"))))
                .toList();
        return new ExpirySummary(number(row, "expiring"), number(row, "expired"), documents);
    }

    private static Document toDocument(Map<String, Object> row) {
        var replaces = (Number) row.get("replaces_document_id");
        return Document.restore(number(row, "id"), new TenantId(number(row, "tenant_id")),
                number(row, "knowledge_base_id"), (String) row.get("filename"), (String) row.get("media_type"),
                ((String) row.get("sha256")).strip(), number(row, "size_bytes"),
                DocumentStatus.valueOf((String) row.get("status")), (String) row.get("error"),
                (int) number(row, "chunk_count"), timestamp(row.get("created_at")),
                replaces == null ? null : replaces.longValue(),
                new Validity(date(row.get("effective_from")), date(row.get("expires_on"))));
    }

    /** 结果是 map 时 PostgreSQL 驱动把 date 读成 java.sql.Date。 */
    private static LocalDate date(Object value) {
        return value instanceof Date date ? date.toLocalDate() : (LocalDate) value;
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
