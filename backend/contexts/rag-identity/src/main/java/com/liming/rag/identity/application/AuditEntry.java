package com.liming.rag.identity.application;

import java.time.OffsetDateTime;

/** 一条审计记录（#145 C2）。 */
public record AuditEntry(Long id, long tenantId, long actorId, String action, String targetType, String targetId,
        String detail, OffsetDateTime occurredAt) {
}
