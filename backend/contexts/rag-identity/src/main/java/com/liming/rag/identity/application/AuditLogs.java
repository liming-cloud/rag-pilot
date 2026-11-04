package com.liming.rag.identity.application;

import java.time.OffsetDateTime;

/** 审计记录的存储端口（#145 C2）：只增，不改；过了保留期按时间删除。 */
public interface AuditLogs {

    void add(AuditEntry entry);

    /** 删掉 {@code before} 之前的记录，返回删除的条数。 */
    int deleteBefore(OffsetDateTime before);
}
