package com.liming.rag.evaluation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.evaluation.api.EvalCase.Kind;
import com.liming.rag.evaluation.api.EvalOutcome.Retrieved;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvalReportTest {

    private static final EvalCase SHELF = new EvalCase("货架", "青柚在哪", Kind.ANSWERABLE, "陈列.md", List.of("03 号"), false);
    private static final EvalCase CASH = new EvalCase("备用金", "备用金多少", Kind.ANSWERABLE, "收银.md", List.of("800"), false);
    private static final EvalCase LEAVE = new EvalCase("年假", "年假怎么请", Kind.UNANSWERABLE, null, List.of(), false);
    private static final EvalCase BONUS = new EvalCase("奖金", "店长奖金", Kind.UNANSWERABLE, null, List.of(), true);

    @Test
    @DisplayName("按 README 口径计算召回、引用、拒答、越权和 p95")
    void computesMetrics() {
        var outcomes = List.of(
                new EvalOutcome(SHELF, List.of(new Retrieved("陈列.md", "放在 03 号货架")), false, null,
                        "在 03 号货架[1]", List.of("陈列.md"), 0.9, 100, 400, 1000),
                new EvalOutcome(CASH, List.of(new Retrieved("收银.md", "交班签字")), false, null,
                        "800 元[1]", List.of("陈列.md"), 0.5, 300, 900, 2000),
                new EvalOutcome(LEAVE, List.of(), true, "NO_EVIDENCE", "", List.of(), null, 200, 50, 50),
                new EvalOutcome(BONUS, List.of(new Retrieved("奖金.md", "基数 3000 元")), false, null,
                        "3000 元[1]", List.of("奖金.md"), 0.8, 400, 600, 1500));

        var report = EvalReport.of(outcomes, Set.of("奖金.md"));

        assertThat(report.recallAt10()).isEqualTo(0.5);
        assertThat(report.citationAccuracy()).isEqualTo(0.5);
        assertThat(report.refusalAccuracy()).isEqualTo(0.5);
        assertThat(report.falseRefusalRate()).isZero();
        assertThat(report.leaks()).isEqualTo(1);
        assertThat(report.searchP95Millis()).isEqualTo(400);
        assertThat(report.firstTokenP95Millis()).isEqualTo(900);
        assertThat(report.askP95Millis()).isEqualTo(2000);
        assertThat(report.failures()).contains("备用金：前 10 条没有召回预期分块", "奖金：越权泄漏", "奖金：应拒答却回答了");
        assertThat(report.toMarkdown()).contains("| 越权泄漏 | 1 | 0 |", "| 检索召回 Recall@10 | 50.0% | ≥ 85% |",
                "| 首字延迟 p95（流式） | 900 ms | < 3 s |");
    }

    @Test
    @DisplayName("按阶段给出耗时 p95，按问法分别统计召回、引用正确、误拒")
    void reportsStagesAndStyles() {
        var colloquial = new EvalCase("货架-口语", "青柚放哪了", Kind.ANSWERABLE, "陈列.md", List.of("03 号"), false, "口语");
        var outcomes = List.of(
                new EvalOutcome(SHELF, List.of(new Retrieved("陈列.md", "放在 03 号货架")), false, null,
                        "在 03 号货架[1]", List.of("陈列.md"), 0.9, 100, 400, 1000,
                        new EvalOutcome.Stages(120L, 300L, 900L)),
                new EvalOutcome(colloquial, List.of(new Retrieved("收银.md", "交班签字")), true, "LOW_RELEVANCE",
                        "", List.of(), 0.1, 100, 300, 300, new EvalOutcome.Stages(80L, 250L, null)));

        var markdown = EvalReport.of(outcomes, Set.of()).toMarkdown();

        assertThat(markdown).contains("| 检索（问题向量化 + 混合检索） | 120 ms |")
                .contains("| 重排 | 300 ms |")
                .contains("| 模型首字（从开始生成算起） | 900 ms |")
                .contains("| 原话 | 1 | 100.0% | 100.0% | 0.0% |")
                .contains("| 口语 | 1 | 0.0% | - | 100.0% |");
    }

    @Test
    @DisplayName("p95 用最近秩法")
    void p95() {
        assertThat(EvalReport.p95(List.of(5L, 1L, 3L))).isEqualTo(5);
        assertThat(EvalReport.p95(List.of())).isZero();
    }
}
