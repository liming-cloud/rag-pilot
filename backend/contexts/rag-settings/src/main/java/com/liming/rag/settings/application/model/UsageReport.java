package com.liming.rag.settings.application.model;

import com.liming.rag.shared.Paged;
import java.time.LocalDate;

/** 一段时间的用量统计：{@code groups} 是按所选维度分组后的一页，{@code total} 是整段合计（不受分页影响）。 */
public record UsageReport(LocalDate from, LocalDate to, UsageGroupBy groupBy, Paged<UsageGroup> groups,
        UsageGroup total) {
}
