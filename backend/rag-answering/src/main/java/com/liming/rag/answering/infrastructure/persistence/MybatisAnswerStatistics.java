package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.insight.AnswerStatistics;
import com.liming.rag.answering.application.insight.UsageStats;
import com.liming.rag.shared.TenantId;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
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
        var byDay = new HashMap<LocalDate, DailyUsageDO>();
        for (var row : mapper.daily(tenant, start, end, zone.getId())) {
            byDay.put(row.day, row);
        }
        var daily = from.datesUntil(to.plusDays(1)).map(day -> {
            var row = byDay.get(day);
            return row == null ? new UsageStats.Day(day, 0, 0, 0)
                    : new UsageStats.Day(day, row.questions, row.refused, row.disliked);
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

    private static UsageStats.Totals toTotals(UsageTotalsDO totals) {
        return new UsageStats.Totals(totals.questions, totals.askers, totals.refused, totals.rated, totals.liked,
                totals.disliked, totals.openReviews);
    }

    private static <T> List<T> ranked(List<LabelCountDO> rows, BiFunction<String, Integer, T> factory) {
        return rows.stream().map(row -> factory.apply(row.label, row.count)).toList();
    }
}
