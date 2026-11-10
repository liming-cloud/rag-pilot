package com.liming.rag.answering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerRecordTest {

    private static final UserId ASKER = new UserId(7);
    private final AnswerRecord record = AnswerRecord.record(new TenantId(1), ASKER, " 青柚在哪？ ",
            new AnswerRecord.Reply("A 区[1]。", false, null, List.of(new Citation(1, 100, 50, "陈列规范", "陈列规范", null)),
                    List.of(), 0.9, null), List.of(3L)).withId(1);

    @Test
    @DisplayName("新记录：问题去掉首尾空格，带上回答、引用和检索范围；还没有评价，待处理")
    void recordsAnswer() {
        assertThat(record.question()).isEqualTo("青柚在哪？");
        assertThat(record.reply().citations()).hasSize(1);
        assertThat(record.knowledgeBaseIds()).containsExactly(3L);
        assertThat(record.rating()).isNull();
        assertThat(record.reviewStatus()).isEqualTo(AnswerRecord.ReviewStatus.OPEN);
    }

    @Test
    @DisplayName("提问人可以评价、改评价、撤销；留言去掉首尾空白，空留言记为没有留言")
    void askerRates() {
        record.rate(ASKER, AnswerRecord.Rating.DOWN, "  门店实际是 A-05 货架  ");
        assertThat(record.rating()).isEqualTo(AnswerRecord.Rating.DOWN);
        assertThat(record.comment()).isEqualTo("门店实际是 A-05 货架");

        record.rate(ASKER, AnswerRecord.Rating.UP, " ");
        assertThat(record.rating()).isEqualTo(AnswerRecord.Rating.UP);
        assertThat(record.comment()).isNull();

        record.clearRating(ASKER);
        assertThat(record.rating()).isNull();
    }

    @Test
    @DisplayName("别人不能评价；留言不超过 500 字")
    void rejectsOthersAndLongComments() {
        assertThatThrownBy(() -> record.rate(new UserId(8), AnswerRecord.Rating.UP, null))
                .isInstanceOf(AnswerRecord.NotAskerException.class);
        assertThatThrownBy(() -> record.clearRating(new UserId(8)))
                .isInstanceOf(AnswerRecord.NotAskerException.class);
        assertThatThrownBy(() -> record.rate(ASKER, AnswerRecord.Rating.DOWN, "很".repeat(501)))
                .isInstanceOf(AnsweringRuleException.class).hasMessageContaining("500");
    }

    @Test
    @DisplayName("管理员标记已处理时记下说明和处理人；重新打开时清掉；说明不超过 500 字")
    void reviews() {
        var admin = new UserId(1);
        record.review(admin, AnswerRecord.ReviewStatus.RESOLVED, "  已上传 2026 版收银规范 ");
        assertThat(record.reviewStatus()).isEqualTo(AnswerRecord.ReviewStatus.RESOLVED);
        assertThat(record.resolutionNote()).isEqualTo("已上传 2026 版收银规范");
        assertThat(record.resolvedBy()).isEqualTo(admin);

        record.review(admin, AnswerRecord.ReviewStatus.OPEN, "忽略");
        assertThat(record.reviewStatus()).isEqualTo(AnswerRecord.ReviewStatus.OPEN);
        assertThat(record.resolutionNote()).isNull();
        assertThat(record.resolvedBy()).isNull();

        assertThatThrownBy(() -> record.review(admin, AnswerRecord.ReviewStatus.RESOLVED, "很".repeat(501)))
                .isInstanceOf(AnsweringRuleException.class).hasMessageContaining("500");
    }
}
