package com.liming.rag.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** audit_log 表，SQL 写在 {@code mapper/identity/AuditLogMapper.xml}。 */
@Mapper
interface AuditLogMapper {

    void insert(@Param("tenantId") long tenantId, @Param("actorId") long actorId, @Param("action") String action,
            @Param("targetType") String targetType, @Param("targetId") String targetId,
            @Param("detail") String detail, @Param("occurredAt") OffsetDateTime occurredAt);

    int deleteBefore(@Param("before") OffsetDateTime before);
}
