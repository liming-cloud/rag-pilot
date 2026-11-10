package com.liming.rag.answering.application.insight;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.answering.application.InMemoryAnswering;
import com.liming.rag.answering.application.TestSettings;
import com.liming.rag.answering.application.review.AnswerReview;
import com.liming.rag.answering.domain.AnswerRecord;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运营概览和周报（#125、D-047）：最近 N 天或某个自然周，按周时与上一周同样的几天对比；导出 CSV。 */
class UsageReportServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final InMemoryAnswering fixture = new InMemoryAnswering();
    private final AnsweringProperties properties = new AnsweringProperties(30, 8, 0.3, false, 0.03,
            Duration.ofSeconds(3));
    private final KnowledgeGapService gaps = new KnowledgeGapService(fixture.gaps, fixture.records, fixture.embeddings,
            fixture.users, fixture.access, TestSettings.RETAIL, properties, Clock.systemUTC());
    private final UsageReportService service = new UsageReportService(fixture.statistics, fixture.reviews, gaps,
            fixture.users, TestSettings.RETAIL, properties, Clock.systemUTC());

    @Test
    @DisplayName("最近 N 天：含今天，默认 30 天；1 到 90 天之外是 INVALID；不和上一段对比")
    void recentDays() {
        var today = LocalDate.now(ZONE);

        service.usage(TENANT, null, null);
        service.usage(TENANT, 7, null);

        assertThat(fixture.statistics.usageCalls).extracting(range -> range[0])
                .containsExactly(today.minusDays(29), today.minusDays(6));
        assertThat(fixture.statistics.totalsCalls).isEmpty();
        assertReason(() -> service.usage(TENANT, 0, null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.usage(TENANT, 91, null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.usage(TENANT, 7, today.with(DayOfWeek.MONDAY).toString()),
                AnsweringException.Reason.INVALID);
    }

    @Test
    @DisplayName("按周：周一开始；本周只统计到今天，并与上一周同样的几天对比；只能看最近 12 周")
    void naturalWeeks() {
        var today = LocalDate.now(ZONE);
        var monday = today.with(DayOfWeek.MONDAY);

        var stats = service.usage(TENANT, null, monday.toString());

        assertThat(fixture.statistics.usageCalls.getFirst()).containsExactly(monday, today);
        assertThat(fixture.statistics.totalsCalls.getFirst())
                .containsExactly(monday.minusWeeks(1), today.minusWeeks(1));
        assertThat(stats.previous()).isNotNull();

        service.usage(TENANT, null, monday.minusWeeks(1).toString());
        assertThat(fixture.statistics.usageCalls.get(1)).containsExactly(monday.minusWeeks(1), monday.minusDays(1));

        assertReason(() -> service.usage(TENANT, null, monday.plusDays(1).toString()),
                AnsweringException.Reason.INVALID);
        assertReason(() -> service.usage(TENANT, null, monday.plusWeeks(1).toString()),
                AnsweringException.Reason.INVALID);
        assertReason(() -> service.usage(TENANT, null, monday.minusWeeks(12).toString()),
                AnsweringException.Reason.INVALID);
        assertReason(() -> service.usage(TENANT, null, "2026/11/02"), AnsweringException.Reason.INVALID);
    }

    @Test
    @DisplayName("能用问答的员工数取当前人数，本期和上一周相同；短时间重复提问：10 分钟内相似度达到门槛的后一题，每题只算一次")
    void activeStaffAndRepeatQuestions() {
        fixture.users.staffCount = 36;
        var same = new float[] {1, 0, 0};
        var close = new float[] {0.9f, 0.1f, 0};
        var other = new float[] {0, 1, 0};
        fixture.statistics.repeats = List.of(
                new AnswerStatistics.RepeatCandidate(11, same, same),
                new AnswerStatistics.RepeatCandidate(12, same, close),
                new AnswerStatistics.RepeatCandidate(12, other, close),
                new AnswerStatistics.RepeatCandidate(13, same, other));
        var monday = LocalDate.now(ZONE).with(DayOfWeek.MONDAY);

        var stats = service.usage(TENANT, null, monday.toString());

        assertThat(stats.totals().activeStaff()).isEqualTo(36);
        assertThat(stats.totals().repeatQuestions()).as("11 和 12 算，13 的相似度不够").isEqualTo(2);
        assertThat(stats.previous().activeStaff()).isEqualTo(36);
        assertThat(fixture.statistics.repeatWindow).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("导出 CSV：带 BOM 和表头；时间按统计时区；以 = + - @ 开头的单元格前加单引号；含逗号、引号的单元格加引号；"
            + "处理状态只给答不上来和差评的")
    void exportsCsv() {
        fixture.users.names.put(7L, "店员小王");
        var at = OffsetDateTime.parse("2026-11-03T16:30:00Z");
        fixture.reviews.rows = List.of(
                review(1, "=SUM(A1)", true, null, "OPEN", at),
                review(2, "冷柜, 温度", false, "UP", "OPEN", at),
                review(3, "说\"没帮助\"", false, "DOWN", "RESOLVED", at));

        var export = service.export(TENANT, 7, null);

        var lines = export.csv().split("\r\n");
        assertThat(export.csv()).startsWith("﻿时间,提问人,问题,结果,评价,留言,引用文档,处理状态");
        assertThat(lines[1]).isEqualTo("2026-11-04 00:30,店员小王,'=SUM(A1),答不上来,,,,待处理");
        assertThat(lines[2]).isEqualTo("2026-11-04 00:30,店员小王,\"冷柜, 温度\",已回答,有帮助,,陈列规范,");
        assertThat(lines[3]).isEqualTo("2026-11-04 00:30,店员小王,\"说\"\"没帮助\"\"\",已回答,没帮助,,陈列规范,已处理");
        assertThat(export.filename()).isEqualTo("运营明细_" + LocalDate.now(ZONE).minusDays(6) + "_"
                + LocalDate.now(ZONE) + ".csv");
        assertThat(fixture.reviews.from).isEqualTo(LocalDate.now(ZONE).minusDays(6).atStartOfDay(ZONE)
                .toOffsetDateTime());
    }

    private static AnswerReview review(long id, String question, boolean refused, String rating, String status,
            OffsetDateTime at) {
        return new AnswerReview(id, 7, null, question, "x", refused, refused ? "NO_EVIDENCE" : null, null, rating,
                null, at, refused ? List.of() : List.of("陈列规范"), null, AnswerRecord.ReviewStatus.valueOf(status), null, null);
    }

    private static void assertReason(ThrowingCallable call, AnsweringException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AnsweringException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
