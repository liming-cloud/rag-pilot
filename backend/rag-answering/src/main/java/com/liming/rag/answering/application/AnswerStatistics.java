package com.liming.rag.answering.application;

import com.liming.rag.shared.TenantId;
import java.time.LocalDate;
import java.time.ZoneId;

/** 回答记录的统计端口。 */
public interface AnswerStatistics {

    /** 统计 {@code from} 到 {@code to}（都含）这几天的记录，日期按 {@code zone} 划分。 */
    UsageStats usage(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone);
}
