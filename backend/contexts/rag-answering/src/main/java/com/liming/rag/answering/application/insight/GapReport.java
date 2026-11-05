package com.liming.rag.answering.application.insight;

import java.time.LocalDate;
import java.util.List;

/**
 * @param records 范围内参与归类的记录数
 * @param truncated 范围内超过 1000 条，只归类了最近的
 */
public record GapReport(LocalDate from, LocalDate to, String zone, GapGrouping grouping, int records,
        boolean truncated, List<GapGroup> groups) {
}
