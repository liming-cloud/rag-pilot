package com.liming.rag.answering.application.insight;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.answering.application.review.AnswerReview;
import com.liming.rag.answering.application.review.AnswerReviews;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.settings.api.StatsZone;
import com.liming.rag.shared.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营概览和周报（#125、D-047）：最近 N 天或某个自然周（周一到周日）的使用统计，按周时与上一周同样的几天对比；
 * 导出这段时间每条问答的 CSV。“一天”“一周”都按部署配置的统计时区划分（D-045）。
 */
@Service
public class UsageReportService {

    /** 最近 N 天最多看多少天。 */
    static final int MAX_DAYS = 90;
    /** 按周最多往前看几周（含本周）。 */
    static final int MAX_WEEKS = 12;
    /** 运营概览里列出几类知识缺口。 */
    static final int TOP_GAPS = 5;
    /** 一次最多导出多少条问答。 */
    static final int MAX_EXPORT_ROWS = 50_000;
    /** 多长时间内又问相近的问题算“重复提问”。 */
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnswerStatistics statistics;
    private final AnswerReviews reviews;
    private final KnowledgeGapService gaps;
    private final UserDirectory users;
    private final StatsZone zones;
    private final double similarity;
    private final Clock clock;

    UsageReportService(AnswerStatistics statistics, AnswerReviews reviews, KnowledgeGapService gaps,
            UserDirectory users, StatsZone zones, AnsweringProperties properties, Clock clock) {
        this.similarity = properties.gaps().similarity();
        this.statistics = statistics;
        this.reviews = reviews;
        this.gaps = gaps;
        this.users = users;
        this.zones = zones;
        this.clock = clock;
    }

    /** 统计的时间段：第一天、最后一天（都含）；按周时 {@code week} 为 true。 */
    record Period(LocalDate from, LocalDate to, boolean week) {
    }

    /**
     * 不开事务：里面的知识缺口归类要调用嵌入模型，不能占着数据库连接等它（#135）。
     *
     * @param days 最近几天（含今天），与 {@code week} 二选一；都不给时看最近 30 天
     * @param week 某周的周一（yyyy-MM-dd），只能是最近 {@value #MAX_WEEKS} 周里的；本周只统计到今天
     * @throws AnsweringException 参数不合法
     */
    public UsageStats usage(TenantId tenantId, @Nullable Integer days, String week) {
        var zone = zones.statsZone();
        var period = period(days, week, zone);
        var stats = statistics.usage(tenantId, period.from(), period.to(), zone);
        int staff = users.countWithPermission(tenantId, IdentityPermissions.CHAT_USE);
        stats = stats.withTotals(stats.totals().with(staff, repeatQuestions(tenantId, period.from(), period.to(), zone)));
        UsageStats.Totals previous = null;
        if (period.week()) {
            long length = period.to().toEpochDay() - period.from().toEpochDay();
            var start = period.from().minusWeeks(1);
            var end = start.plusDays(length);
            previous = statistics.totals(tenantId, start, end, zone).with(staff,
                    repeatQuestions(tenantId, start, end, zone));
        }
        return stats.withComparison(previous, gaps.top(tenantId, period.from(), period.to(), zone, TOP_GAPS));
    }

    /** 短时间重复提问的题数：此前 10 分钟内同一员工问过、向量相似度达到缺口归类门槛的题，每题只算一次。 */
    private int repeatQuestions(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone) {
        return (int) statistics.repeatCandidates(tenantId, from, to, zone, REPEAT_WINDOW).stream()
                .filter(pair -> QuestionClusters.cosine(pair.previous(), pair.current()) >= similarity)
                .map(AnswerStatistics.RepeatCandidate::recordId).distinct().count();
    }

    /**
     * 这段时间的问答明细，每条一行：时间、提问人、问题、结果、评价、留言、引用文档、处理状态。
     * 以 = + - @ 开头的单元格前加单引号，防止在 Excel 里被当成公式执行。
     */
    @Transactional(readOnly = true)
    public UsageExport export(TenantId tenantId, @Nullable Integer days, String week) {
        var zone = zones.statsZone();
        var period = period(days, week, zone);
        var rows = reviews.between(tenantId, period.from().atStartOfDay(zone).toOffsetDateTime(),
                period.to().plusDays(1).atStartOfDay(zone).toOffsetDateTime(), MAX_EXPORT_ROWS);
        var names = users.summaries(tenantId, rows.stream().map(AnswerReview::userId).distinct().toList());
        var csv = new StringBuilder("\uFEFF");
        line(csv, List.of("时间", "提问人", "问题", "结果", "评价", "留言", "引用文档", "处理状态"));
        for (var row : rows) {
            var asker = names.get(row.userId());
            boolean followUp = row.refused() || "DOWN".equals(row.rating());
            line(csv, List.of(row.createdAt().atZoneSameInstant(zone).format(TIME),
                    asker == null ? "" : asker.displayName(), row.question(), row.refused() ? "答不上来" : "已回答",
                    "UP".equals(row.rating()) ? "有帮助" : "DOWN".equals(row.rating()) ? "没帮助" : "",
                    row.comment() == null ? "" : row.comment(), String.join("、", row.citedDocuments()),
                    !followUp ? "" : row.reviewStatus() == AnswerRecord.ReviewStatus.RESOLVED ? "已处理" : "待处理"));
        }
        return new UsageExport("运营明细_" + period.from() + "_" + period.to() + ".csv", csv.toString());
    }

    Period period(@Nullable Integer days, String week, ZoneId zone) {
        var today = LocalDate.now(clock.withZone(zone));
        if (days != null && week != null) {
            throw invalid("最近几天和按周只能选一个");
        }
        if (week == null) {
            int span = days == null ? 30 : days;
            if (span < 1 || span > MAX_DAYS) {
                throw invalid("统计天数只能是 1 到 " + MAX_DAYS + " 天");
            }
            return new Period(today.minusDays(span - 1L), today, false);
        }
        LocalDate monday;
        try {
            monday = LocalDate.parse(week);
        } catch (DateTimeParseException e) {
            throw invalid("周的格式应为周一的日期 yyyy-MM-dd");
        }
        var thisMonday = today.with(DayOfWeek.MONDAY);
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY || monday.isAfter(thisMonday)
                || monday.isBefore(thisMonday.minusWeeks(MAX_WEEKS - 1L))) {
            throw invalid("只能看最近 " + MAX_WEEKS + " 周，周要从周一开始");
        }
        var sunday = monday.plusDays(6);
        return new Period(monday, sunday.isAfter(today) ? today : sunday, true);
    }

    private static void line(StringBuilder csv, List<String> cells) {
        csv.append(cells.stream().map(UsageReportService::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    private static String cell(String value) {
        var text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private static AnsweringException invalid(String message) {
        return new AnsweringException(AnsweringException.Reason.INVALID, message);
    }
}
