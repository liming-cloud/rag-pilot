package com.liming.rag.data.application.query;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具调用的审计（规范第 8 节，保留 1 年，与管理操作审计同一张表）。单独一个事务：远程调用不放在事务里，
 * 调用结束后才记。说明里只有业务条件、范围摘要、行数和状态，不含令牌、返回的数据和员工的问题。
 */
@Component
public class ToolCallAudit {

    static final String ACTION = "DATA_TOOL_CALLED";
    static final String TARGET_TYPE = "DATA_TOOL";

    private final AuditTrail audit;

    public ToolCallAudit(AuditTrail audit) {
        this.audit = audit;
    }

    @Transactional
    public void record(TenantId tenantId, UserId asker, String tool, String detail) {
        audit.record(tenantId, asker, ACTION, TARGET_TYPE, tool, detail);
    }
}
