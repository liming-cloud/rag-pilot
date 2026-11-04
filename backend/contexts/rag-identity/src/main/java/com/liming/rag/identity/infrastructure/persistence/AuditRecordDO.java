package com.liming.rag.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** 查看用的一条审计记录（#145）：audit_log 的列加操作人的显示名，只在 infrastructure 包内读取。 */
@NullUnmarked
final class AuditRecordDO {

    Long id;
    Long actorId;
    String actorName;
    String action;
    String targetType;
    String targetId;
    String detail;
    OffsetDateTime occurredAt;
}
