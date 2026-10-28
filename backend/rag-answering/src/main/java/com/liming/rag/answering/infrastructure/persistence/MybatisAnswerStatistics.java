package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.answering.application.AnswerStatistics;
import com.liming.rag.answering.application.UsageStats;
import com.liming.rag.common.TenantId;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Repository;

@Repository
class MybatisAnswerStatistics implements AnswerStatistics {

    /** 排行只取前几名。 */
    private static final int TOP = 10;

    private final AnswerRecordMapper mapper;

    MybatisAnswerStatistics(AnswerRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UsageStats usage(TenantId tenantId, LocalDate from, LocalDate to) {
        long tenant = tenantId.value();
        var start = from.atStartOfDay(AnswerLogService.STATS_ZONE).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(AnswerLogService.STATS_ZONE).toOffsetDateTime();

        var totals = mapper.totals(tenant, start, end);
        var byDay = new HashMap<LocalDate, Map<String, Object>>();
        for (var row : mapper.daily(tenant, start, end, AnswerLogService.STATS_ZONE.getId())) {
            byDay.put(date(row.get("day")), row);
        }
        var daily = from.datesUntil(to.plusDays(1)).map(day -> {
            var row = byDay.getOrDefault(day, Map.of());
            return new UsageStats.Day(day, count(row, "questions"), count(row, "refused"), count(row, "disliked"));
        }).toList();

        return new UsageStats(from, to,
                new UsageStats.Totals(count(totals, "questions"), count(totals, "askers"), count(totals, "refused"),
                        count(totals, "rated"), count(totals, "liked"), count(totals, "disliked"),
                        count(totals, "open_reviews")),
                daily,
                ranked(mapper.refusalReasons(tenant, start, end), UsageStats.ReasonCount::new),
                ranked(mapper.topRefusedQuestions(tenant, start, end, TOP), UsageStats.QuestionCount::new),
                ranked(mapper.topCitedDocuments(tenant, start, end, TOP), UsageStats.DocumentCount::new));
    }

    private static <T> List<T> ranked(List<Map<String, Object>> rows,
            BiFunction<String, Integer, T> factory) {
        return rows.stream().map(row -> factory.apply((String) row.get("label"), count(row, "count"))).toList();
    }

    private static int count(Map<String, Object> row, String column) {
        var value = row.get(column);
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static LocalDate date(Object value) {
        return value instanceof Date date ? date.toLocalDate() : (LocalDate) value;
    }
}
