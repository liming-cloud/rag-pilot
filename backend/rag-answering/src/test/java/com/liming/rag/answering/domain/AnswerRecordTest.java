package com.liming.rag.answering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerRecordTest {

    private static final UserId ASKER = new UserId(7);
    private final AnswerRecord record = AnswerRecord.restore(1, new TenantId(1), ASKER, null, null);

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
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
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
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
    }
}
