package com.liming.rag.identity.infrastructure.persistence;

import com.liming.rag.identity.application.AuditEntry;
import com.liming.rag.identity.application.AuditLogs;
import com.liming.rag.identity.application.AuditRecord;
import com.liming.rag.identity.application.Paged;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
class MybatisAuditLogs implements AuditLogs {

    private final AuditLogMapper mapper;

    MybatisAuditLogs(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void add(AuditEntry entry) {
        mapper.insert(entry.tenantId(), entry.actorId(), entry.action(), entry.targetType(), entry.targetId(),
                entry.detail(), entry.occurredAt());
    }

    @Override
    public Paged<AuditRecord> page(long tenantId, OffsetDateTime since, @Nullable String action, Paged.Request page) {
        var rows = mapper.page(tenantId, since, action, page.offset(), page.size()).stream()
                .map(row -> new AuditRecord(row.id, row.actorId, row.actorName, row.action, row.targetType,
                        row.targetId, row.detail, row.occurredAt))
                .toList();
        return new Paged<>(rows, mapper.count(tenantId, since, action), page.page(), page.size());
    }

    @Override
    public int deleteBefore(OffsetDateTime before) {
        return mapper.deleteBefore(before);
    }
}
