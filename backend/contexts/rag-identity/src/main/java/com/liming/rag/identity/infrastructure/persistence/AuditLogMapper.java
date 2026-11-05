package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** audit_log 表：写入和清理用通用方法；连表带出操作人名字的分页查询写在 {@code mapper/identity/AuditLogMapper.xml}。 */
@Mapper
interface AuditLogMapper extends BaseMapper<AuditLogDO> {


    List<AuditRecordDO> page(@Param("tenantId") long tenantId, @Param("since") OffsetDateTime since,
            @Param("action") String action, @Param("offset") long offset, @Param("size") int size);

    long count(@Param("tenantId") long tenantId, @Param("since") OffsetDateTime since, @Param("action") String action);

}
