package com.liming.rag.answering.application;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.UserId;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 回答记录与反馈：记下每次问答和实际检索的库（D-047），提问人评价，管理员筛选和处理。 */
class AnswerLogServiceTest {

    private static final UserId ASKER = new UserId(7);
    private static final UserId OTHER = new UserId(8);
    private static final UserId ADMIN = new UserId(1);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final InMemoryAnswering fixture = new InMemoryAnswering();
    private final AnswerLogService service = new AnswerLogService(fixture.records, fixture.reviews, fixture.users,
            fixture.access, TestSettings.RETAIL);

    {
        fixture.access.grant(7, 1, "门店规范").grant(7, 2, "商品知识");
        fixture.users.names.put(7L, "店员小王");
    }

    @Test
    @DisplayName("记录问答：点名的库里只留有权访问的、去重；没点名时记全部可访问的库；问题去掉首尾空格")
    void recordsSearchedScope() {
        var named = service.record(TENANT, ASKER, " 青柚在哪？ ", refused(), List.of(2L, 2L, 3L));
        var all = service.record(TENANT, ASKER, "冷柜温度？", refused());

        assertThat(named.id()).isNotNull();
        assertThat(fixture.records.rows.get(named.id()).knowledgeBaseIds()).containsExactly(2L);
        assertThat(fixture.records.rows.get(named.id()).question()).isEqualTo("青柚在哪？");
        assertThat(fixture.records.rows.get(all.id()).knowledgeBaseIds()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("评价：只有提问人能评价，别人按回答不存在处理；评分只能是 UP、DOWN；留言超长是 INVALID；可以撤销")
    void ratesOwnAnswersOnly() {
        long id = service.record(TENANT, ASKER, "青柚在哪？", refused()).id();

        service.rate(TENANT, ASKER, id, "DOWN", "  没说哪个货架  ");
        assertThat(fixture.records.rows.get(id).rating()).isEqualTo(AnswerRecord.Rating.DOWN);
        assertThat(fixture.records.rows.get(id).comment()).isEqualTo("没说哪个货架");

        assertReason(() -> service.rate(TENANT, OTHER, id, "UP", null), AnsweringException.Reason.NOT_FOUND);
        assertReason(() -> service.rate(TENANT, ASKER, id, "up", null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.rate(TENANT, ASKER, id, "UP", "长".repeat(AnswerRecord.MAX_COMMENT_LENGTH + 1)),
                AnsweringException.Reason.INVALID);
        assertReason(() -> service.clearRating(TENANT, OTHER, id), AnsweringException.Reason.NOT_FOUND);
        assertThat(fixture.records.rows.get(id).rating()).isEqualTo(AnswerRecord.Rating.DOWN);

        service.clearRating(TENANT, ASKER, id);
        assertThat(fixture.records.rows.get(id).rating()).isNull();
        assertReason(() -> service.rate(TENANT, ASKER, 404, "UP", null), AnsweringException.Reason.NOT_FOUND);
    }

    @Test
    @DisplayName("处理：标记已处理时记下说明和处理人，重新打开时清掉；状态不认识、说明超长是 INVALID")
    void marksReview() {
        long id = service.record(TENANT, ASKER, "青柚在哪？", refused()).id();

        service.markReview(TENANT, ADMIN, id, "RESOLVED", " 已补陈列图 ");
        assertThat(fixture.records.rows.get(id).reviewStatus()).isEqualTo(AnswerRecord.ReviewStatus.RESOLVED);
        assertThat(fixture.records.rows.get(id).resolutionNote()).isEqualTo("已补陈列图");
        assertThat(fixture.records.rows.get(id).resolvedBy()).isEqualTo(ADMIN);

        service.markReview(TENANT, ADMIN, id, "OPEN", null);
        assertThat(fixture.records.rows.get(id).resolutionNote()).isNull();
        assertThat(fixture.records.rows.get(id).resolvedBy()).isNull();

        assertReason(() -> service.markReview(TENANT, ADMIN, id, "resolved", null), AnsweringException.Reason.INVALID);
        assertReason(() -> service.markReview(TENANT, ADMIN, id, "RESOLVED", "长".repeat(501)),
                AnsweringException.Reason.INVALID);
        assertThat(service.questionOf(TENANT, id)).isEqualTo("青柚在哪？");
    }

    @Test
    @DisplayName("管理端列表：范围、状态不区分大小写；最近几天按统计时区从当天零点算；关键词去掉空格；带上提问人的显示名")
    void pagesReviews() {
        fixture.reviews.rows = List.of(new AnswerReview(5, 7, null, "青柚在哪？", "x", true, "NO_EVIDENCE", null, null,
                null, OffsetDateTime.now(), List.of(), null, "OPEN", null, null));

        var page = service.review(TENANT, "refused", "Open", 7, "  青柚 ", 2, 10);

        assertThat(page.items()).extracting(AnswerReview::askerName).containsExactly("店员小王");
        var query = fixture.reviews.query;
        assertThat(query.filter()).isEqualTo(AnswerReview.Filter.REFUSED);
        assertThat(query.status()).isEqualTo(AnswerReview.Status.OPEN);
        assertThat(query.keyword()).isEqualTo("青柚");
        assertThat(query.since()).isEqualTo(LocalDate.now(ZONE).minusDays(6).atStartOfDay(ZONE).toOffsetDateTime());
        assertThat(query.page()).isEqualTo(new Paged.Request(2, 10));

        service.review(TENANT, "all", "all", null, null, 1, 20);
        assertThat(fixture.reviews.query.since()).isNull();
        assertReason(() -> service.review(TENANT, "liked", "all", null, null, 1, 20), AnsweringException.Reason.INVALID);
        assertReason(() -> service.review(TENANT, "all", "done", null, null, 1, 20), AnsweringException.Reason.INVALID);
        assertReason(() -> service.review(TENANT, "all", "all", 0, null, 1, 20), AnsweringException.Reason.INVALID);
        assertReason(() -> service.review(TENANT, "all", "all", 91, null, 1, 20), AnsweringException.Reason.INVALID);
    }

    private static AskResult refused() {
        return AskResult.refused(RefusalReason.NO_EVIDENCE, null, TestSettings.RETAIL_REFUSAL);
    }

    private static void assertReason(ThrowingCallable call, AnsweringException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AnsweringException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
