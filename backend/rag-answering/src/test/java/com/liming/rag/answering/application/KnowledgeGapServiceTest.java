package com.liming.rag.answering.application;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.GapRecords.GapRecord;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.shared.UserId;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 知识缺口（#125、D-047）：拒答和差评按意思归类，按待处理次数排序；嵌入模型不可用时按原文分组。 */
class KnowledgeGapServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final UserId ADMIN = new UserId(1);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T10:00:00+08:00");

    private final InMemoryAnswering fixture = new InMemoryAnswering();
    private final KnowledgeGapService service = new KnowledgeGapService(fixture.gaps, fixture.records,
            fixture.embeddings, fixture.users, fixture.access, TestSettings.RETAIL,
            new AnsweringProperties(30, 8, 0.3, false, 0.03, Duration.ofSeconds(3)));

    {
        fixture.users.names.put(7L, "店员小王");
        fixture.access.names.put(1L, "门店规范");
        fixture.embeddings.topic("冷柜", 1, 0, 0).topic("冰柜", 1, 0, 0).topic("青柚", 0, 1, 0);
        // 按时间倒序，与 SQL 一致。
        fixture.gaps.rows = List.of(
                gap(6, "冷柜温度要多少？", 0, true, "OPEN", List.of(1L)),
                gap(5, "青柚在哪？", 1, false, "RESOLVED", List.of(1L, 9L)),
                gap(4, "冰柜温度设几度？", 2, true, "OPEN", List.of()),
                gap(3, "冷柜温度要多少？", 3, false, "RESOLVED", List.of(1L)));
    }

    @Test
    @DisplayName("按意思归类：同义问法归为一类，代表问题是类里出现最多的原文，带上次数、待处理数、库名和提问人")
    void groupsBySimilarity() {
        var report = service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE);

        assertThat(report.grouping()).isEqualTo(KnowledgeGapService.Grouping.SEMANTIC);
        assertThat(report.records()).isEqualTo(4);
        assertThat(report.truncated()).isFalse();
        var cold = report.groups().getFirst();
        assertThat(cold.question()).isEqualTo("冷柜温度要多少？");
        assertThat(cold.id()).as("代表问题最近的一条").isEqualTo(6);
        assertThat(cold.count()).isEqualTo(3);
        assertThat(cold.openCount()).isEqualTo(2);
        assertThat(cold.refusedCount()).isEqualTo(2);
        assertThat(cold.dislikedCount()).isEqualTo(1);
        assertThat(cold.phrasings()).isEqualTo(2);
        assertThat(cold.knowledgeBases()).as("已删除的库不显示").containsExactly("门店规范");
        assertThat(cold.records()).extracting(KnowledgeGapService.Entry::askerName).containsOnly("店员小王");
        assertThat(report.groups()).extracting(KnowledgeGapService.Group::question)
                .containsExactly("冷柜温度要多少？", "青柚在哪？");
    }

    @Test
    @DisplayName("嵌入模型不可用时退回按原文分组，同义问法各成一类")
    void fallsBackToTextGrouping() {
        fixture.embeddings.unavailable = true;

        var report = service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE);

        assertThat(report.grouping()).isEqualTo(KnowledgeGapService.Grouping.TEXT);
        assertThat(report.groups()).hasSize(3);
    }

    @Test
    @DisplayName("超过上限时只归类最近的，并标出被截断；没有记录时不调用嵌入模型")
    void marksTruncatedAndSkipsEmpty() {
        fixture.gaps.total = KnowledgeGapService.MAX_RECORDS + 1;
        fixture.gaps.rows = java.util.stream.IntStream.range(0, KnowledgeGapService.MAX_RECORDS)
                .mapToObj(i -> gap(i, "问题" + i, i, true, "OPEN", List.of())).toList();
        assertThat(service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE).truncated())
                .isTrue();

        fixture.gaps.rows = List.of();
        fixture.gaps.total = -1;
        int calls = fixture.embeddings.calls;
        assertThat(service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE).groups())
                .isEmpty();
        assertThat(fixture.embeddings.calls).isEqualTo(calls);
    }

    @Test
    @DisplayName("按天数查看：1 到 90 天；open 只列还有待处理记录的类，all 列全部；其他状态是 INVALID")
    void reportsRecentDays() {
        assertThat(service.report(TENANT, 30, "all").groups()).hasSize(2);
        assertThat(service.report(TENANT, 30, "open").groups()).extracting(KnowledgeGapService.Group::question)
                .containsExactly("冷柜温度要多少？");
        assertReason(() -> service.report(TENANT, 0, "open"), AnsweringException.Reason.INVALID);
        assertReason(() -> service.report(TENANT, 91, "open"), AnsweringException.Reason.INVALID);
        assertReason(() -> service.report(TENANT, 30, "OPEN"), AnsweringException.Reason.INVALID);
    }

    @Test
    @DisplayName("运营概览的前几类：只看还有待处理的，次数是待处理数")
    void topOpenGroups() {
        assertThat(service.top(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE, 5))
                .containsExactly(new UsageStats.QuestionCount("冷柜温度要多少？", 2));
    }

    @Test
    @DisplayName("整类标记已处理：只改还没处理的，返回改了几条；没有记录、超过上限、有记录不存在都拒绝")
    void resolvesGroup() {
        long open = fixture.records.record(TENANT, new UserId(7), "冷柜温度要多少？", refused(), List.of());
        long done = fixture.records.record(TENANT, new UserId(7), "冷柜温度设几度？", refused(), List.of());
        service.resolve(TENANT, ADMIN, List.of(done), "已补");
        int saves = fixture.records.reviewSaves;

        assertThat(service.resolve(TENANT, ADMIN, List.of(open, done, open), "已补温度表")).isEqualTo(1);
        assertThat(fixture.records.reviewSaves - saves).isEqualTo(1);
        assertThat(fixture.records.rows.get(open).reviewStatus()).isEqualTo(AnswerRecord.ReviewStatus.RESOLVED);
        assertThat(fixture.records.rows.get(done).resolutionNote()).as("已处理的不变").isEqualTo("已补");

        assertReason(() -> service.resolve(TENANT, ADMIN, List.of(), null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.resolve(TENANT, ADMIN, java.util.Collections.nCopies(
                KnowledgeGapService.MAX_RESOLVE + 1, open), null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.resolve(TENANT, ADMIN, List.of(404L), null), AnsweringException.Reason.NOT_FOUND);
    }

    private static GapRecord gap(long id, String question, int hoursAgo, boolean refused, String status,
            List<Long> knowledgeBaseIds) {
        return new GapRecord(id, 7, question, NOW.minusHours(hoursAgo), refused, refused ? null : "没帮助",
                refused ? List.of() : List.of("陈列规范"), status, knowledgeBaseIds);
    }

    private static Answer refused() {
        return Answer.refused(Answer.RefusalReason.NO_EVIDENCE, null, TestSettings.RETAIL_REFUSAL);
    }

    private static void assertReason(ThrowingCallable call, AnsweringException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AnsweringException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
