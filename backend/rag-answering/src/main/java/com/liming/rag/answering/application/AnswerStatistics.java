package com.liming.rag.answering.application;

import com.liming.rag.common.TenantId;
import java.time.LocalDate;

/** 回答记录的统计端口。 */
public interface AnswerStatistics {

    /** 统计 {@code from} 到 {@code to}（都含）这几天的记录，日期按北京时间。 */
    UsageStats usage(TenantId tenantId, LocalDate from, LocalDate to);
}
