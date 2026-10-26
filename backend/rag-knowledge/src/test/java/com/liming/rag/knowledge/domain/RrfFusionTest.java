package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    @Test
    @DisplayName("两路都靠前的结果排第一，分数为两路倒数名次之和")
    void rewardsAgreementBetweenRoutes() {
        var fused = RrfFusion.fuse(List.of(List.of(1L, 2L, 3L), List.of(2L, 4L)), 10);

        assertThat(fused).extracting(RrfFusion.Scored::id).containsExactly(2L, 1L, 4L, 3L);
        assertThat(fused.getFirst().score()).isEqualTo(1.0 / 62 + 1.0 / 61);
    }

    @Test
    @DisplayName("按上限截断；同分保持首次出现的顺序；空输入返回空")
    void limitsAndKeepsOrderOnTies() {
        assertThat(RrfFusion.fuse(List.of(List.of(7L, 8L), List.of(8L, 7L)), 1))
                .extracting(RrfFusion.Scored::id).containsExactly(7L);
        assertThat(RrfFusion.fuse(List.of(List.of(), List.of()), 5)).isEmpty();
    }
}
