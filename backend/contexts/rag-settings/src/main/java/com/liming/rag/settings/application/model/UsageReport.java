package com.liming.rag.settings.application.model;

import java.time.LocalDate;
import java.util.List;

/** 一段时间的用量统计：{@code groups} 按所选维度分组，{@code total} 是整段合计。 */
public record UsageReport(LocalDate from, LocalDate to, UsageGroupBy groupBy, List<UsageGroup> groups,
        UsageGroup total) {
}
