package com.liming.rag.knowledge.infrastructure.persistence;

import com.liming.rag.knowledge.application.Window;
import com.liming.rag.knowledge.application.KnowledgeBaseSummary;
import com.liming.rag.knowledge.application.ExpirySummary;
import com.liming.rag.knowledge.application.ExpiringDocument;
import com.liming.rag.knowledge.application.DocumentRow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.knowledge.api.DocumentFreshness;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.knowledge.application.KnowledgeQueries;
import com.liming.rag.shared.Paged;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
class MybatisKnowledgeQueries implements KnowledgeQueries {

    private final KnowledgeQueryMapper mapper;
    private final KnowledgeBaseMemberMapper members;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentMapper documents;

    MybatisKnowledgeQueries(KnowledgeQueryMapper mapper, KnowledgeBaseMemberMapper members,
            KnowledgeBaseMapper knowledgeBases, DocumentMapper documents) {
        this.documents = documents;
        this.mapper = mapper;
        this.members = members;
        this.knowledgeBases = knowledgeBases;
    }

    @Override
    public List<KnowledgeBaseRef> accessible(Grantee grantee) {
        return members.accessible(grantee.tenantId().value(), grantee.userId().value(), grantee.roleIds()).stream()
                .map(row -> new KnowledgeBaseRef(row.getId(), row.getName())).toList();
    }

    @Override
    public Optional<KnowledgeBaseRef> manageable(TenantId tenantId, @Nullable Grantee grantee, long knowledgeBaseId) {
        boolean all = grantee == null;
        var row = members.manageable(tenantId.value(), all, all ? null : grantee.userId().value(),
                all ? List.of() : grantee.roleIds(), knowledgeBaseId);
        return Optional.ofNullable(row).map(found -> new KnowledgeBaseRef(found.getId(), found.getName()));
    }

    @Override
    public Map<Long, DocumentFreshness> freshness(TenantId tenantId, Collection<Long> documentIds, LocalDate today) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return documents.selectList(new LambdaQueryWrapper<DocumentDO>()
                        .select(DocumentDO::getId, DocumentDO::getCreatedAt, DocumentDO::getExpiresOn)
                        .eq(DocumentDO::getTenantId, tenantId.value())
                        .in(DocumentDO::getId, documentIds))
                .stream().collect(Collectors.toMap(DocumentDO::getId, row -> new DocumentFreshness(
                        row.getCreatedAt(), row.getExpiresOn() != null && row.getExpiresOn().isBefore(today))));
    }

    @Override
    public Map<Long, String> names(TenantId tenantId, Collection<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeBases.selectList(new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getTenantId, tenantId.value())
                        .in(KnowledgeBaseDO::getId, knowledgeBaseIds))
                .stream().collect(Collectors.toMap(KnowledgeBaseDO::getId, KnowledgeBaseDO::getName));
    }

    @Override
    public Paged<KnowledgeBaseSummary> knowledgeBases(TenantId tenantId, @Nullable Grantee grantee,
            @Nullable String keyword, Validity.@Nullable State validity, Window window, Paged.Request page) {
        boolean all = grantee == null;
        Long userId = all ? null : grantee.userId().value();
        List<Long> roleIds = all ? List.of() : grantee.roleIds();
        var state = validity == null ? null : validity.name();
        var rows = mapper.knowledgeBases(tenantId.value(), all, userId, roleIds, keyword, state, window.today(),
                window.warnUntil(), page.offset(), page.size());
        long total = mapper.countKnowledgeBases(tenantId.value(), all, userId, roleIds, keyword, state,
                window.today(), window.warnUntil());
        return new Paged<>(rows.stream().map(row -> new KnowledgeBaseSummary(row.id, row.name, row.documentCount,
                row.failedCount, row.expiringCount, row.expiredCount, row.roleGrantCount, row.memberCount)).toList(),
                total, page.page(), page.size());
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
        int warningDays = (int) ChronoUnit.DAYS.between(window.today(), window.warnUntil());
        return new Paged<>(rows.stream().map(row -> {
            var document = MybatisDocumentRepository.toDomain(row);
            return DocumentRow.of(document, row.replacesFilename,
                    document.validity().state(window.today(), warningDays));
        }).toList(), total, page.page(), page.size());
    }

    @Override
    public ExpirySummary expirySummary(TenantId tenantId, @Nullable Grantee grantee, Window window, int limit) {
        boolean all = grantee == null;
        Long userId = all ? null : grantee.userId().value();
        List<Long> roleIds = all ? List.of() : grantee.roleIds();
        var row = mapper.expirySummary(tenantId.value(), all, userId, roleIds, window.today(), window.warnUntil());
        var documents = mapper.expiringDocuments(tenantId.value(), all, userId, roleIds, window.today(),
                window.warnUntil(), limit).stream()
                .map(document -> new ExpiringDocument(document.knowledgeBaseId, document.knowledgeBaseName,
                        document.id, document.filename, document.expiresOn,
                        Validity.State.valueOf(document.validity)))
                .toList();
        return new ExpirySummary(row.expiring, row.expired, documents);
    }
}
