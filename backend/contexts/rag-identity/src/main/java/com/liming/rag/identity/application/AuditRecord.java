package com.liming.rag.identity.application;

import java.time.OffsetDateTime;

/** 查看用的一条审计记录（#145）：带操作人的显示名，账号已删除时为 null。 */
public record AuditRecord(long id, long actorId, String actorName, String action, String targetType, String targetId,
        String detail, OffsetDateTime occurredAt) {
}
