package com.liming.rag.identity.application.audit;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/** 一条审计记录（#145 C2）。 */
public record AuditEntry(@Nullable Long id, long tenantId, long actorId, String action, String targetType, String targetId,
        String detail, OffsetDateTime occurredAt) {
}
