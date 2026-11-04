package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.knowledge.application.KnowledgeQueries;
import com.liming.rag.knowledge.application.Paged;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
class MybatisKnowledgeQueries implements KnowledgeQueries {

    private final KnowledgeQueryMapper mapper;
    private final KnowledgeBaseMemberMapper members;
    private final KnowledgeBaseMapper knowledgeBases;

    MybatisKnowledgeQueries(KnowledgeQueryMapper mapper, KnowledgeBaseMemberMapper members,
            KnowledgeBaseMapper knowledgeBases) {
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
    public boolean isAccessible(Grantee grantee, long knowledgeBaseId) {
        return members.isAccessible(grantee.tenantId().value(), grantee.userId().value(), grantee.roleIds(),
                knowledgeBaseId);
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
