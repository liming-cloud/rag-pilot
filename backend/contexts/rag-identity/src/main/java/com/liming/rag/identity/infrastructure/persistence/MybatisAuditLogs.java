package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.identity.application.audit.AuditEntry;
import com.liming.rag.identity.application.audit.AuditLogs;
import com.liming.rag.identity.application.audit.AuditRecord;
import com.liming.rag.shared.Paged;
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
        var row = new AuditLogDO();
        row.tenantId = entry.tenantId();
        row.actorId = entry.actorId();
        row.action = entry.action();
        row.targetType = entry.targetType();
        row.targetId = entry.targetId();
        row.detail = entry.detail();
        row.occurredAt = entry.occurredAt();
        mapper.insert(row);
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
        return mapper.delete(new LambdaQueryWrapper<AuditLogDO>().lt(AuditLogDO::getOccurredAt, before));
    }
}
