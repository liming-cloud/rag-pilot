package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerJudgeTest {

    @Test
    @DisplayName("解析评判结果：结论和理由；中文冒号或英文冒号都认；看不懂时判为错误并保留原文")
    void parsesVerdict() {
        assertThat(OpenAiCompatibleJudge.parse("结论：部分正确\n理由：只答出了活动名称。"))
                .isEqualTo(new AnswerJudge.Judgement(AnswerJudge.Verdict.PARTIAL, "只答出了活动名称。"));
        assertThat(OpenAiCompatibleJudge.parse("结论: 正确\n理由: 与参考答案一致").verdict())
                .isEqualTo(AnswerJudge.Verdict.CORRECT);
        assertThat(OpenAiCompatibleJudge.parse("结论：错误\n理由：数字不对").verdict()).isEqualTo(AnswerJudge.Verdict.WRONG);

        var unclear = OpenAiCompatibleJudge.parse("我觉得还行");
        assertThat(unclear.verdict()).isEqualTo(AnswerJudge.Verdict.WRONG);
        assertThat(unclear.reason()).contains("看不懂评判输出").contains("我觉得还行");
    }
}
