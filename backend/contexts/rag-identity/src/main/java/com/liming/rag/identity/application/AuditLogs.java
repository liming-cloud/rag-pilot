package com.liming.rag.identity.application;

import java.time.OffsetDateTime;

/** 审计记录的存储端口（#145 C2）：只增，不改；过了保留期按时间删除。 */
public interface AuditLogs {

    void add(AuditEntry entry);

    /**
     * 一页审计记录，新的在前，带操作人的显示名。
     *
     * @param action 只看这一类操作，为 null 不限
     */
    Paged<AuditRecord> page(long tenantId, OffsetDateTime since, String action, Paged.Request page);

    /** 删掉 {@code before} 之前的记录，返回删除的条数。 */
    int deleteBefore(OffsetDateTime before);
}
