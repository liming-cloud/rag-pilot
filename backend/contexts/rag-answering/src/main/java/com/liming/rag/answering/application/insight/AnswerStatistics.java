package com.liming.rag.answering.application.insight;

import com.liming.rag.shared.TenantId;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 回答记录的统计端口。 */
public interface AnswerStatistics {

    /** 统计 {@code from} 到 {@code to}（都含）这几天的记录，日期按 {@code zone} 划分。 */
    UsageStats usage(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone);

    /** 只要汇总，用于和上一周对比。 */
    UsageStats.Totals totals(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone);

    /**
     * 这几天里“同一员工在 {@code window} 内、此前还问过”的每一题，配上此前问的每个问题的向量（两边都有向量的才列）。
     * 是不是意思相近由调用方按向量判断；一题可以出现多次。
     */
    List<RepeatCandidate> repeatCandidates(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone,
            Duration window);

    /** @param recordId 后问的那条回答记录 */
    record RepeatCandidate(long recordId, float[] previous, float[] current) {
    }
}
