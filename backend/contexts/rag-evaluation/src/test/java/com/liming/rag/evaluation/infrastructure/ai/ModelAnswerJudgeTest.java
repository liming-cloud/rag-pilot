package com.liming.rag.evaluation.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.evaluation.api.AnswerJudge;
import com.liming.rag.evaluation.api.JudgeUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelAnswerJudgeTest {

    @Test
    @DisplayName("解析评判结果：结论和理由；中文冒号或英文冒号都认；看不懂时判为错误并保留原文")
    void parsesVerdict() {
        assertThat(ModelAnswerJudge.parse("结论：部分正确\n理由：只答出了活动名称。"))
                .isEqualTo(new AnswerJudge.Judgement(AnswerJudge.Verdict.PARTIAL, "只答出了活动名称。"));
        assertThat(ModelAnswerJudge.parse("结论: 正确\n理由: 与参考答案一致").verdict())
                .isEqualTo(AnswerJudge.Verdict.CORRECT);
        assertThat(ModelAnswerJudge.parse("结论：错误\n理由：数字不对").verdict()).isEqualTo(AnswerJudge.Verdict.WRONG);

        var unclear = ModelAnswerJudge.parse("我觉得还行");
        assertThat(unclear.verdict()).isEqualTo(AnswerJudge.Verdict.WRONG);
        assertThat(unclear.reason()).contains("看不懂评判输出").contains("我觉得还行");
    }

    @Test
    @DisplayName("先输出思考过程的模型：跳过 <think> 里的内容，取最后一个结论")
    void parsesReasoningOutput() {
        var output = "<think>先看结论：错误？不对，多出的内容不扣分。</think>\n结论：正确\n理由：要点齐全";

        assertThat(ModelAnswerJudge.parse(output))
                .isEqualTo(new AnswerJudge.Judgement(AnswerJudge.Verdict.CORRECT, "要点齐全"));
        assertThat(ModelAnswerJudge.parse("分析：结论：部分正确吗？再想想。\n结论：错误\n理由：数字不对").verdict())
                .isEqualTo(AnswerJudge.Verdict.WRONG);
    }

    @Test
    @DisplayName("评判模型调用失败：转成评测自己的异常，错误码仍是 MODEL_UNAVAILABLE，不泄露 rag-ai 的类型")
    void modelFailureBecomesJudgeException() {
        var judge = new ModelAnswerJudge((system, user) -> {
            throw new ModelCallException("底层失败");
        });

        assertThatThrownBy(() -> judge.judge("问题", "参考", "回答"))
                .isInstanceOf(JudgeUnavailableException.class)
                .isNotInstanceOf(ModelCallException.class)
                .hasMessageContaining("答案评判失败")
                .extracting(e -> ((JudgeUnavailableException) e).code())
                .isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    @DisplayName("把问题、参考答案和系统回答交给模型，按返回的结论评判")
    void judgesWithModelOutput() {
        var judge = new ModelAnswerJudge((system, user) -> {
            assertThat(user).contains("问题：Q").contains("参考答案：R").contains("系统回答：A");
            return "结论：正确\n理由：一致";
        });

        assertThat(judge.judge("Q", "R", "A").verdict()).isEqualTo(AnswerJudge.Verdict.CORRECT);
    }
}
