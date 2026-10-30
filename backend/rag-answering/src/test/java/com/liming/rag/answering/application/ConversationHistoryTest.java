package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.api.Answer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationHistoryTest {

    private static ConversationHistory.Entry answered(String question, String text) {
        return new ConversationHistory.Entry(question, Answer.answered(text, List.of(), 0.9));
    }

    @Test
    @DisplayName("按“员工 / 助手”逐轮写出，回答去掉引用标记")
    void formatsTurns() {
        var history = ConversationHistory.of(List.of(answered("冷柜温度要多少？", "保持 2–6℃[1]。")), 6, 3000);

        assertThat(history.text()).isEqualTo("员工：冷柜温度要多少？\n助手：保持 2–6℃。");
    }

    @Test
    @DisplayName("拒答的轮次只带问题和一句说明，不带拒答话术；停止或出错的轮次也只带问题")
    void refusedTurnsKeepOnlyQuestion() {
        var history = ConversationHistory.of(List.of(
                new ConversationHistory.Entry("年假怎么请？", Answer.refused(Answer.RefusalReason.LOW_RELEVANCE, 0.01)),
                new ConversationHistory.Entry("那病假呢？", null)), 6, 3000);

        assertThat(history.text()).isEqualTo("员工：年假怎么请？\n助手：（这一轮没有找到依据）\n员工：那病假呢？\n助手：（这一轮没有结果）");
        assertThat(history.text()).doesNotContain("联系店长");
    }

    @Test
    @DisplayName("只带最近几轮；每条回答最多 300 字；总长超出时丢最早的轮次")
    void keepsRecentTurnsWithinBudget() {
        var entries = List.of(answered("第一问", "一".repeat(400)), answered("第二问", "二"), answered("第三问", "三"));

        assertThat(ConversationHistory.of(entries, 2, 3000).text()).doesNotContain("第一问").contains("第二问", "第三问");
        assertThat(ConversationHistory.of(entries, 3, 3000).text()).contains("一".repeat(300) + "…").doesNotContain("一".repeat(301));
        assertThat(ConversationHistory.of(entries, 3, 40).text()).doesNotContain("第一问").contains("第三问");
        assertThat(ConversationHistory.of(List.of(), 3, 3000).isEmpty()).isTrue();
    }
}
