package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.identity.application.audit.AuditRecord;
import com.liming.rag.identity.application.audit.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 查看管理操作的审计记录（#145）。 */
@RestController
class AuditController {

    private final AuditService audits;
    private final CurrentUser currentUser;

    AuditController(AuditService audits, CurrentUser currentUser) {
        this.audits = audits;
        this.currentUser = currentUser;
    }

    /** 最近几天（默认 30，最多 365）的记录，可按操作类型筛选，分页（D-039）。 */
    @SaCheckPermission(IdentityPermissions.AUDIT_VIEW)
    @GetMapping("/api/audit-logs")
    PageView<AuditRecord> list(@RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageView.of(audits.page(currentUser.tenantId(), days, action, page, size), record -> record);
    }
}
