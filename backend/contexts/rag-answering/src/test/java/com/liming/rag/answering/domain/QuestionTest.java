package com.liming.rag.answering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuestionTest {

    @Test
    @DisplayName("问题去掉首尾空格；为空或超过 500 个字时拒绝")
    void validatesLength() {
        assertThat(new Question("  青柚在哪？ ").text()).isEqualTo("青柚在哪？");
        assertThat(new Question("问".repeat(500)).text()).hasSize(500);
        assertThatThrownBy(() -> new Question("  ")).isInstanceOf(AnsweringRuleException.class)
                .hasMessage("问题不能为空，且不超过 500 个字").hasFieldOrPropertyWithValue("code", "ANSWERING_INVALID");
        assertThatThrownBy(() -> new Question(null)).isInstanceOf(AnsweringRuleException.class);
        assertThatThrownBy(() -> new Question("问".repeat(501))).isInstanceOf(AnsweringRuleException.class);
        assertThat(Question.fits(" 问 ")).isTrue();
        assertThat(Question.fits("问".repeat(501))).isFalse();
    }
}
