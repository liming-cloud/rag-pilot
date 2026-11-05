package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** audit_log 的一行，单表写入和清理用。带操作人名字的查询结果见 {@link AuditRecordDO}。 */
@TableName("audit_log")
@NullUnmarked
final class AuditLogDO {

    @TableId(type = IdType.AUTO)
    Long id;
    Long tenantId;
    Long actorId;
    String action;
    String targetType;
    String targetId;
    String detail;
    OffsetDateTime occurredAt;

    OffsetDateTime getOccurredAt() {
        return occurredAt;
    }
}
