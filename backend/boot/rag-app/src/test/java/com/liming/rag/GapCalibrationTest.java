package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** 归类门槛校准里“同一道题的不同问法”的认定（D-047）。 */
class GapCalibrationTest {

    @Test
    @DisplayName("后缀问法（-口语、-错字）和前缀问法（有效期-）归到原题，其余各自一家")
    void groupsVariants() {
        var ids = Set.of("临期折扣", "临期折扣-错字", "有效期-临期折扣", "会员日-口语", "会员日", "有效期-积分规则", "年假");

        assertThat(GapCalibration.family("临期折扣-错字", ids)).isEqualTo("临期折扣");
        assertThat(GapCalibration.family("有效期-临期折扣", ids)).isEqualTo("临期折扣");
        assertThat(GapCalibration.family("会员日-口语", ids)).isEqualTo("会员日");
        assertThat(GapCalibration.family("有效期-积分规则", ids)).isEqualTo("有效期-积分规则");
        assertThat(GapCalibration.family("年假", ids)).isEqualTo("年假");
    }

    @Test
    @DisplayName("余弦相似度：同向为 1，正交为 0，零向量为 0")
    void cosine() {
        assertThat(GapCalibration.cosine(new float[] {1, 1}, new float[] {2, 2})).isCloseTo(1, Offset.offset(1e-6));
        assertThat(GapCalibration.cosine(new float[] {1, 0}, new float[] {0, 1})).isZero();
        assertThat(GapCalibration.cosine(new float[] {0, 0}, new float[] {0, 1})).isZero();
    }
}
