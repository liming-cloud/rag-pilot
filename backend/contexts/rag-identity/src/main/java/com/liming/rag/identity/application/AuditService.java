package com.liming.rag.identity.application;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计记录（#145 C2）：在调用方的事务里写，操作回滚时记录也回滚，不会出现“记了却没做”。保留 1 年，每天清理一次。
 */
@Service
class AuditService implements AuditTrail {

    static final Duration RETENTION = Duration.ofDays(365);

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogs logs;
    private final Clock clock;

    AuditService(AuditLogs logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(TenantId tenantId, UserId actor, String action, String targetType, String targetId,
            String detail) {
        logs.add(new AuditEntry(null, tenantId.value(), actor.value(), action, targetType, targetId, detail,
                OffsetDateTime.now(clock)));
    }

    /** 每天凌晨 3 点 10 分（服务器时区）清理超过保留期的记录，返回删除的条数。 */
    @Scheduled(cron = "0 10 3 * * *")
    @Transactional
    public int purge() {
        int deleted = logs.deleteBefore(OffsetDateTime.now(clock).minus(RETENTION));
        if (deleted > 0) {
            LOG.info("清理了 {} 条超过保留期的审计记录", deleted);
        }
        return deleted;
    }
}
