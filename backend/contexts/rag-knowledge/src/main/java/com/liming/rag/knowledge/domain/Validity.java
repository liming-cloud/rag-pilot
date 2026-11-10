package com.liming.rag.knowledge.domain;

import java.time.LocalDate;

/**
 * 文档的有效期（#124、D-046）：生效日期、失效日期都可空，空表示不限。失效日期当天仍有效，次日起不参与检索；
 * 未到生效日期的同样不参与。日期按部署的统计时区解释，“今天”由调用方给出。
 *
 * @param effectiveFrom 生效日期（含当天）
 * @param expiresOn     失效日期（含当天）
 */
public record Validity(LocalDate effectiveFrom, LocalDate expiresOn) {

    /** 长期有效：现有文档迁移后都是这个。 */
    public static final Validity ALWAYS = new Validity(null, null);

    /** @throws KnowledgeRuleException 失效日期早于生效日期时 */
    public Validity {
        if (effectiveFrom != null && expiresOn != null && expiresOn.isBefore(effectiveFrom)) {
            throw KnowledgeRuleException.invalid("失效日期不能早于生效日期");
        }
    }

    /** 管理端显示的状态；只有 ACTIVE 和 EXPIRING 参与检索。 */
    public enum State {
        ACTIVE, EXPIRING, EXPIRED, PENDING
    }

    /** 这一天能不能被检索和引用。 */
    public boolean isCurrent(LocalDate today) {
        return (effectiveFrom == null || !effectiveFrom.isAfter(today))
                && (expiresOn == null || !expiresOn.isBefore(today));
    }

    /**
     * @param warningDays 失效日期在今天起这么多天内（含）算“即将到期”
     */
    public State state(LocalDate today, int warningDays) {
        if (effectiveFrom != null && effectiveFrom.isAfter(today)) {
            return State.PENDING;
        }
        if (expiresOn != null && expiresOn.isBefore(today)) {
            return State.EXPIRED;
        }
        if (expiresOn != null && !expiresOn.isAfter(today.plusDays(warningDays))) {
            return State.EXPIRING;
        }
        return State.ACTIVE;
    }
}
