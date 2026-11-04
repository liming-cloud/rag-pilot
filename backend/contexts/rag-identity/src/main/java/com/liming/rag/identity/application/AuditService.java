package com.liming.rag.identity.application;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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
public class AuditService implements AuditTrail {

    static final Duration RETENTION = Duration.ofDays(365);
    /** 最多看多少天，与保留期一致。 */
    static final int MAX_DAYS = 365;
    /** 操作类型是各上下文定的常量名，如 ROLE_CREATED。 */
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z_]{0,49}");

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

    /**
     * 最近 {@code days} 天的审计记录（audit:view），新的在前。
     *
     * @param action 只看这一类操作（如 ROLE_CREATED），为空不限
     * @throws IdentityException 天数不在 1 到 365 之间、操作类型格式不对时为 INVALID
     */
    @Transactional(readOnly = true)
    public Paged<AuditRecord> page(TenantId tenantId, int days, @Nullable String action, int page, int size) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IdentityException(IdentityException.Reason.INVALID, "天数只能是 1 到 " + MAX_DAYS + " 天");
        }
        var filter = action == null || action.isBlank() ? null : action.strip();
        if (filter != null && !ACTION.matcher(filter).matches()) {
            throw new IdentityException(IdentityException.Reason.INVALID, "操作类型不认识");
        }
        return logs.page(tenantId.value(), OffsetDateTime.now(clock).minusDays(days), filter,
                new Paged.Request(page, size));
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
