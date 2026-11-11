package com.liming.rag.answering.application.insight;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.answering.application.InMemoryAnswering;
import com.liming.rag.answering.application.TestSettings;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.insight.GapRecords.GapRecord;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.UserId;
import java.time.Clock;
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
            new AnsweringProperties(30, 8, 0.3, false, 0.03, Duration.ofSeconds(3)), Clock.systemUTC());

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

        assertThat(report.grouping()).isEqualTo(GapGrouping.SEMANTIC);
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
        assertThat(cold.knowledgeBaseRefs()).as("带 id，已删除的库不列").containsExactly(new KnowledgeBaseRef(1L, "门店规范"));
        assertThat(cold.records()).extracting(GapEntry::askerName).containsOnly("店员小王");
        assertThat(report.groups()).extracting(GapGroup::question)
                .containsExactly("冷柜温度要多少？", "青柚在哪？");
    }

    @Test
    @DisplayName("记录回答时存下的问题向量直接用，不再调用嵌入模型；没存的（旧记录、后台没算成）只算一次并写回")
    void reusesStoredVectors() {
        float[] cold = {1, 0, 0};
        fixture.gaps.rows = List.of(
                gap(6, "冷柜温度要多少？", 0, true, "OPEN", List.of(1L), cold),
                gap(5, "青柚在哪？", 1, false, "RESOLVED", List.of(1L), new float[] {0, 1, 0}),
                gap(4, "冰柜温度设几度？", 2, true, "OPEN", List.of()),
                gap(3, "冷柜温度要多少？", 3, false, "RESOLVED", List.of(1L)),
                gap(2, "冰柜温度设几度？", 4, true, "OPEN", List.of()));

        var report = service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE);

        assertThat(report.groups()).extracting(GapGroup::question)
                .containsExactly("冷柜温度要多少？", "青柚在哪？");
        assertThat(fixture.embeddings.embedded).as("同一原文已有向量的不再算").containsExactly("冰柜温度设几度？");
        assertThat(fixture.gaps.savedVectors).as("算出的向量写回这段原文的每条记录").containsOnlyKeys(4L, 2L, 3L);

        fixture.embeddings.unavailable = true;
        fixture.gaps.rows = fixture.gaps.rows.stream().map(row -> new GapRecord(row.id(), row.userId(),
                row.question(), row.createdAt(), row.refused(), row.comment(), row.citedDocuments(),
                row.reviewStatus(), row.knowledgeBaseIds(),
                row.questionVector() != null ? row.questionVector() : fixture.gaps.savedVectors.get(row.id())))
                .toList();
        assertThat(service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE).grouping())
                .as("向量都存好以后，嵌入模型不可用也能按意思归类").isEqualTo(GapGrouping.SEMANTIC);
    }

    @Test
    @DisplayName("嵌入模型不可用时退回按原文分组，同义问法各成一类")
    void fallsBackToTextGrouping() {
        fixture.embeddings.unavailable = true;

        var report = service.report(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE);

        assertThat(report.grouping()).isEqualTo(GapGrouping.TEXT);
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
        assertThat(service.report(TENANT, 30, "open").groups()).extracting(GapGroup::question)
                .containsExactly("冷柜温度要多少？");
        assertReason(() -> service.report(TENANT, 0, "open"), AnsweringException.Reason.INVALID);
        assertReason(() -> service.report(TENANT, 91, "open"), AnsweringException.Reason.INVALID);
        assertReason(() -> service.report(TENANT, 30, "OPEN"), AnsweringException.Reason.INVALID);
    }

    @Test
    @DisplayName("运营概览的前几类：只看还有待处理的，次数是待处理数")
    void topOpenGroups() {
        fixture.gaps.rows = List.of(
                gap(6, "冷柜温度要多少？", 0, true, "OPEN", List.of(1L), new float[] {1, 0, 0}),
                gap(5, "青柚在哪？", 1, false, "RESOLVED", List.of(1L, 9L)),
                gap(4, "冰柜温度设几度？", 2, true, "OPEN", List.of(), new float[] {1, 0, 0}),
                gap(3, "冷柜温度要多少？", 3, false, "RESOLVED", List.of(1L)));
        assertThat(service.top(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE, 5))
                .containsExactly(new UsageStats.QuestionCount("冷柜温度要多少？", 2));
    }

    @Test
    @DisplayName("运营概览只用已存的向量，不调嵌入模型、不写库；没有向量的按原文归类（#200、D-067）")
    void topUsesStoredVectorsOnly() {
        assertThat(service.top(TENANT, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 4), ZONE, 5))
                .as("都没有向量：冷柜、冰柜按原文各算一类")
                .containsExactlyInAnyOrder(new UsageStats.QuestionCount("冷柜温度要多少？", 1),
                        new UsageStats.QuestionCount("冰柜温度设几度？", 1));
        assertThat(fixture.embeddings.calls).isZero();
        assertThat(fixture.gaps.savedVectors).isEmpty();
    }

    @Test
    @DisplayName("整类标记已处理：只改还没处理的，返回改了几条；没有记录、超过上限、有记录不存在都拒绝")
    void resolvesGroup() {
        long open = fixture.records.add(AnswerRecord.record(TENANT, new UserId(7), "冷柜温度要多少？", refused().reply(), List.of())).id();
        long done = fixture.records.add(AnswerRecord.record(TENANT, new UserId(7), "冷柜温度设几度？", refused().reply(), List.of())).id();
        service.resolve(TENANT, ADMIN, List.of(done), "已补");
        int saves = fixture.records.reviewSaves;

        assertThat(service.resolve(TENANT, ADMIN, List.of(open, done, open), "已补温度表")).isEqualTo(1);
        assertThat(fixture.records.reviewSaves - saves).isEqualTo(1);
        assertThat(fixture.records.batchSaves).as("整类一次写入").isEqualTo(2);
        assertThat(fixture.records.rows.get(open).reviewStatus()).isEqualTo(AnswerRecord.ReviewStatus.RESOLVED);
        assertThat(fixture.records.rows.get(done).resolutionNote()).as("已处理的不变").isEqualTo("已补");

        assertReason(() -> service.resolve(TENANT, ADMIN, List.of(), null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.resolve(TENANT, ADMIN, java.util.Collections.nCopies(
                KnowledgeGapService.MAX_RESOLVE + 1, open), null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.resolve(TENANT, ADMIN, List.of(404L), null), AnsweringException.Reason.NOT_FOUND);
    }

    private static GapRecord gap(long id, String question, int hoursAgo, boolean refused, String status,
            List<Long> knowledgeBaseIds) {
        return gap(id, question, hoursAgo, refused, status, knowledgeBaseIds, null);
    }

    private static GapRecord gap(long id, String question, int hoursAgo, boolean refused, String status,
            List<Long> knowledgeBaseIds, float[] vector) {
        return new GapRecord(id, 7, question, NOW.minusHours(hoursAgo), refused, refused ? null : "没帮助",
                refused ? List.of() : List.of("陈列规范"), AnswerRecord.ReviewStatus.valueOf(status), knowledgeBaseIds, vector);
    }

    private static AskResult refused() {
        return AskResult.refused(RefusalReason.NO_EVIDENCE, null, TestSettings.RETAIL_REFUSAL);
    }

    private static void assertReason(ThrowingCallable call, AnsweringException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AnsweringException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
