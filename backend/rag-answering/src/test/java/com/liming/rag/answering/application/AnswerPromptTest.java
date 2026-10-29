package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerPromptTest {

    @Test
    @DisplayName("资料只够回答一部分时答能答的部分并说明缺什么；综合类问题只归纳资料里的事实；只有完全无关才回 NO_ANSWER")
    void allowsPartialAndSynthesizedAnswers() {
        assertThat(AnswerPrompt.SYSTEM)
                .contains("只能回答问题的一部分时")
                .contains("说明哪部分资料里没有提到")
                .contains("对照、归纳")
                .contains("资料与问题完全无关时，只回复 NO_ANSWER")
                .doesNotContain("资料不足以回答，只回复 NO_ANSWER");
    }

    @Test
    @DisplayName("说明资料里没有提到的那句话可以不带引用，其余每句都要带")
    void exemptsMissingInfoSentenceFromCitation() {
        assertThat(List.of(AnswerPrompt.SYSTEM.split("\n")))
                .anyMatch(line -> line.contains("资料里没有提到") && line.contains("不用标注"));
    }
}
