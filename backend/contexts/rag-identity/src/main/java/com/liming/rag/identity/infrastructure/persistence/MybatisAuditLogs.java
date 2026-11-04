package com.liming.rag.identity.infrastructure.persistence;

import com.liming.rag.identity.application.AuditEntry;
import com.liming.rag.identity.application.AuditLogs;
import java.time.OffsetDateTime;
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
    public int deleteBefore(OffsetDateTime before) {
        return mapper.deleteBefore(before);
    }
}
