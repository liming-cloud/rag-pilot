package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.AnswerStatistics;
import com.liming.rag.answering.application.UsageStats;
import com.liming.rag.shared.TenantId;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
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
    public UsageStats usage(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone) {
        long tenant = tenantId.value();
        var start = from.atStartOfDay(zone).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        var totals = mapper.totals(tenant, start, end);
        var byDay = new HashMap<LocalDate, Map<String, Object>>();
        for (var row : mapper.daily(tenant, start, end, zone.getId())) {
            byDay.put(date(row.get("day")), row);
        }
        var daily = from.datesUntil(to.plusDays(1)).map(day -> {
            var row = byDay.getOrDefault(day, Map.of());
            return new UsageStats.Day(day, count(row, "questions"), count(row, "refused"), count(row, "disliked"));
        }).toList();

        return new UsageStats(from, to, zone.getId(), toTotals(totals), null, daily,
                ranked(mapper.refusalReasons(tenant, start, end), UsageStats.ReasonCount::new),
                List.of(),
                ranked(mapper.topCitedDocuments(tenant, start, end, TOP), UsageStats.DocumentCount::new));
    }

    @Override
    public UsageStats.Totals totals(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone) {
        return toTotals(mapper.totals(tenantId.value(), from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
    }

    private static UsageStats.Totals toTotals(Map<String, Object> totals) {
        return new UsageStats.Totals(count(totals, "questions"), count(totals, "askers"), count(totals, "refused"),
                count(totals, "rated"), count(totals, "liked"), count(totals, "disliked"),
                count(totals, "open_reviews"));
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
