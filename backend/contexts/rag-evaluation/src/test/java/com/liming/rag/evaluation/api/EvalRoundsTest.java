package com.liming.rag.evaluation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.evaluation.api.EvalCase.Kind;
import com.liming.rag.evaluation.api.EvalOutcome.Retrieved;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvalRoundsTest {

    private static final EvalCase SHELF = new EvalCase("货架", "青柚在哪", Kind.ANSWERABLE, "陈列.md", List.of("03 号"), false);
    private static final EvalCase CASH = new EvalCase("备用金", "备用金多少", Kind.ANSWERABLE, "收银.md", List.of("800"), false);
    private static final EvalCase LEAVE = new EvalCase("年假", "年假怎么请", Kind.UNANSWERABLE, null, List.of(), false);

    private static EvalOutcome answered(EvalCase evalCase, String document, String answer, double top) {
        return new EvalOutcome(evalCase, List.of(new Retrieved(document, answer)), false, null, answer + "[1]",
                List.of(document), top, 100, 500, 1000);
    }

    private static EvalOutcome refused(EvalCase evalCase, String reason, Double top) {
        var retrieved = evalCase.kind() == Kind.ANSWERABLE
                ? List.of(new Retrieved(evalCase.expectedDocuments().getFirst(), evalCase.keywords().getFirst()))
                : List.<Retrieved>of();
        return new EvalOutcome(evalCase, retrieved, true, reason, "找不到", List.of(), top, 100, 300, 300);
    }

    private static EvalReport round(EvalOutcome... outcomes) {
        return EvalReport.of(List.of(outcomes), Set.of());
    }

    @Test
    @DisplayName("多轮汇总：各项指标给出均值、最小、最大；越权泄漏按各轮合计")
    void summarizesMetricsAcrossRounds() {
        var first = round(answered(SHELF, "陈列.md", "03 号", 0.9), answered(CASH, "收银.md", "800", 0.8),
                refused(LEAVE, "LOW_RELEVANCE", 0.01));
        var second = round(answered(SHELF, "陈列.md", "03 号", 0.9), refused(CASH, "UNCITED", 0.8),
                refused(LEAVE, "LOW_RELEVANCE", 0.01));

        var rounds = EvalRounds.of(List.of(first, second));

        assertThat(rounds.falseRefusalRate().mean()).isEqualTo(0.25);
        assertThat(rounds.falseRefusalRate().min()).isEqualTo(0.0);
        assertThat(rounds.falseRefusalRate().max()).isEqualTo(0.5);
        assertThat(rounds.refusalAccuracy().min()).isEqualTo(1.0);
        assertThat(rounds.leaks()).isZero();
        assertThat(rounds.toMarkdown())
                .contains("共 2 轮，每轮 3 题")
                .contains("| 误拒率 | 25.0% | 0.0% | 50.0% | ≤ 3% |")
                .contains("| 第 2 轮 |");
    }

    @Test
    @DisplayName("逐题稳定性：每轮都失败的是稳定失败，部分轮次失败的是偶发失败")
    void separatesStableAndFlakyFailures() {
        var first = round(answered(SHELF, "陈列.md", "03 号", 0.9), refused(CASH, "LOW_RELEVANCE", 0.1),
                refused(LEAVE, "LOW_RELEVANCE", 0.01));
        var second = round(refused(SHELF, "UNCITED", 0.9), refused(CASH, "LOW_RELEVANCE", 0.1),
                refused(LEAVE, "LOW_RELEVANCE", 0.01));

        var rounds = EvalRounds.of(List.of(first, second));

        assertThat(rounds.stableFailures()).containsExactly("备用金");
        assertThat(rounds.flakyFailures()).containsExactly("货架");
        assertThat(rounds.toMarkdown())
                .contains("稳定失败（2 / 2 轮）：备用金")
                .contains("偶发失败：货架（1 / 2 轮）");
    }

    @Test
    @DisplayName("门槛推算：低分拒答的题分数够门槛就算回答；模型拒答和没有引用按实际；回答了的题分数低于门槛算拒答")
    void estimatesMetricsAtOtherThresholds() {
        var report = round(answered(SHELF, "陈列.md", "03 号", 0.9), refused(CASH, "LOW_RELEVANCE", 0.12),
                refused(LEAVE, "LOW_RELEVANCE", 0.04));

        var at010 = EvalRounds.estimate(List.of(report), 0.10);
        var at020 = EvalRounds.estimate(List.of(report), 0.20);
        var at095 = EvalRounds.estimate(List.of(report), 0.95);

        assertThat(at010.falseRefusalRate()).isEqualTo(0.0);
        assertThat(at010.refusalAccuracy()).isEqualTo(1.0);
        assertThat(at020.falseRefusalRate()).isEqualTo(0.5);
        assertThat(at095.falseRefusalRate()).isEqualTo(1.0);

        var modelDeclined = round(refused(CASH, "MODEL_DECLINED", 0.9));
        assertThat(EvalRounds.estimate(List.of(modelDeclined), 0.10).falseRefusalRate()).isEqualTo(1.0);
        assertThat(EvalRounds.of(List.of(report)).toMarkdown()).contains("### 拒答门槛推算");
    }

    @Test
    @DisplayName("只跑一轮时，报告与单轮报告相同，另附门槛推算")
    void singleRoundKeepsReport() {
        var report = round(answered(SHELF, "陈列.md", "03 号", 0.9), refused(LEAVE, "LOW_RELEVANCE", 0.01));

        var markdown = EvalRounds.of(List.of(report)).toMarkdown();

        assertThat(markdown).startsWith(report.toMarkdown()).doesNotContain("多轮汇总");
    }
}
