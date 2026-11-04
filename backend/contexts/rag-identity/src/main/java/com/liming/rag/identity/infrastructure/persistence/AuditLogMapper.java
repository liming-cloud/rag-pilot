package com.liming.rag.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** audit_log 表，SQL 写在 {@code mapper/identity/AuditLogMapper.xml}。 */
@Mapper
interface AuditLogMapper {

    void insert(@Param("tenantId") long tenantId, @Param("actorId") long actorId, @Param("action") String action,
            @Param("targetType") String targetType, @Param("targetId") String targetId,
            @Param("detail") String detail, @Param("occurredAt") OffsetDateTime occurredAt);

    List<AuditRecordDO> page(@Param("tenantId") long tenantId, @Param("since") OffsetDateTime since,
            @Param("action") String action, @Param("offset") long offset, @Param("size") int size);

    long count(@Param("tenantId") long tenantId, @Param("since") OffsetDateTime since, @Param("action") String action);

    int deleteBefore(@Param("before") OffsetDateTime before);
}
