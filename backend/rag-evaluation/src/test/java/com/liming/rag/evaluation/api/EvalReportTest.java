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
                "| 首字延迟 p95（流式） | 900 ms | 参考（D-030） |");
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
    @DisplayName("统计低分改写：触发次数、救回的可回答题、被改写后答了的应拒答题、改写耗时")
    void reportsRewrites() {
        var rescued = new EvalOutcome(SHELF, List.of(new Retrieved("陈列.md", "放在 03 号货架")), false, null,
                "在 03 号货架[1]", List.of("陈列.md"), 0.9, 100, 400, 1000,
                new EvalOutcome.Stages(120L, 300L, 900L, 1500L),
                new EvalOutcome.Rewrite("REWRITTEN", "青柚气泡水在哪个货架？", 14, 1200L, 100L, 200L));
        var stillRefused = new EvalOutcome(LEAVE, List.of(), true, "LOW_RELEVANCE", "", List.of(), 0.04, 100, 300,
                300, new EvalOutcome.Stages(80L, 250L, null, 1100L),
                new EvalOutcome.Rewrite("REWRITTEN", "员工年假如何申请？", 9, 800L, 100L, 200L));
        var risky = new EvalOutcome(BONUS, List.of(), false, null, "基数 3000 元[1]", List.of("奖金.md"), 0.5, 100,
                300, 300, new EvalOutcome.Stages(80L, 250L, 700L, 1200L),
                new EvalOutcome.Rewrite("REWRITTEN", "店长奖金怎么算？", 8, 900L, 100L, 200L));
        var skipped = new EvalOutcome(LEAVE, List.of(), true, "LOW_RELEVANCE", "", List.of(), 0.001, 100, 300, 300,
                new EvalOutcome.Stages(80L, 250L, null, null),
                new EvalOutcome.Rewrite("SKIPPED_BELOW_FLOOR", null, 0, null, null, null));
        var timedOut = new EvalOutcome(LEAVE, List.of(), true, "LOW_RELEVANCE", "", List.of(), 0.05, 100, 300, 3300,
                new EvalOutcome.Stages(80L, 250L, null, 3000L),
                new EvalOutcome.Rewrite("TIMED_OUT", null, 0, 3000L, null, null));

        var markdown = EvalReport.of(List.of(rescued, stillRefused, risky, skipped, timedOut), Set.of()).toMarkdown();

        assertThat(markdown).contains("走到低分改写 5 题：低于下限跳过 1 题，超时 1 题，没给出有效改写 0 题，已改写 3 题")
                .contains("可回答题救回 1 题")
                .contains("应拒答题被改写后回答了 1 题")
                .contains("| 低分改写合计（改写 + 重新检索、重排） | 3000 ms |")
                .contains("| 　改写·模型 | 3000 ms |")
                .contains("| 　改写·重新检索 | 100 ms |")
                .contains("| 　改写·重新重排 | 200 ms |")
                .contains("改写输出最长 14 字")
                .contains("员工年假如何申请？");
    }

    @Test
    @DisplayName("多篇原文：任一进前 10 算召回，全部进前 10 才算全部召回；引用任一原文算引用正确；答案按评判统计")
    void reportsMultiDocumentRecallAndJudgements() {
        var twoDocs = new EvalCase("双文档", "两篇新闻都提到了什么", Kind.ANSWERABLE, List.of("a.txt", "b.txt"), List.of(),
                false, "双文档", "两篇都提到防控近视");
        var oneDoc = new EvalCase("单文档", "活动针对谁", Kind.ANSWERABLE, List.of("c.txt"), List.of(), false, "单文档",
                "儿童青少年");
        var outcomes = List.of(
                new EvalOutcome(twoDocs, List.of(new Retrieved("a.txt", "防控近视"), new Retrieved("x.txt", "无关")), false,
                        null, "提到防控近视[1]", List.of("a.txt"), 0.9, 100, 300, 900, null, null,
                        new EvalOutcome.Judgement("PARTIAL", "只覆盖了一篇")),
                new EvalOutcome(oneDoc, List.of(new Retrieved("c.txt", "儿童青少年")), false, null, "针对儿童青少年[1]",
                        List.of("c.txt"), 0.95, 100, 300, 900, null, null, new EvalOutcome.Judgement("CORRECT", "一致")),
                new EvalOutcome(new EvalCase("单文档2", "活动叫什么", Kind.ANSWERABLE, List.of("d.txt"), List.of(), false,
                        "单文档", "启明行动"), List.of(new Retrieved("d.txt", "启明行动")), false, null, "叫护眼行动[1]",
                        List.of("d.txt"), 0.9, 100, 300, 900, null, null, new EvalOutcome.Judgement("WRONG", "名称不对")));

        var report = EvalReport.of(outcomes, Set.of());

        assertThat(report.recallAt10()).isEqualTo(1.0);
        assertThat(report.citationAccuracy()).isEqualTo(1.0);
        assertThat(report.toMarkdown())
                .contains("| 多篇原文全部召回 | 0.0% |")
                .contains("答案评判 3 题：正确 1 题（33.3%），部分正确 1 题（33.3%），错误 1 题（33.3%）")
                .contains("单文档2：答案错误（名称不对）")
                .contains("双文档：答案部分正确（只覆盖了一篇）");
    }

    @Test
    @DisplayName("p95 用最近秩法")
    void p95() {
        assertThat(EvalReport.p95(List.of(5L, 1L, 3L))).isEqualTo(5);
        assertThat(EvalReport.p95(List.of())).isZero();
    }

    @Test
    @DisplayName("列出首字超过 3 秒的题和其中模型首字的耗时，逐题明细写首字延迟")
    void reportsSlowFirstTokens() {
        var slow = new EvalOutcome(SHELF, List.of(new Retrieved("陈列.md", "放在 03 号货架")), false, null,
                "在 03 号货架[1]", List.of("陈列.md"), 0.9, 100, 7300, 8000, new EvalOutcome.Stages(120L, 300L, 6800L));
        var slower = new EvalOutcome(CASH, List.of(new Retrieved("收银.md", "备用金 800 元")), false, null,
                "800 元[1]", List.of("收银.md"), 0.9, 100, 9100, 9500, new EvalOutcome.Stages(110L, 280L, 8650L));
        var fast = new EvalOutcome(CASH, List.of(new Retrieved("收银.md", "备用金 800 元")), false, null,
                "800 元[1]", List.of("收银.md"), 0.9, 100, 2900, 3500, new EvalOutcome.Stages(110L, 280L, 2400L));

        var markdown = EvalReport.of(List.of(slow, fast, slower), Set.of()).toMarkdown();

        assertThat(markdown).contains("首字超过 3 s 的题 2 / 3：备用金 9100 ms（模型首字 8650 ms）、货架 7300 ms（模型首字 6800 ms）")
                .contains("| 题 | 拒答 | 最高分 | 首字 | 改写为 |")
                .contains("| 货架 | 否 | 0.900 | 7300 ms |");
    }

    @Test
    @DisplayName("拒答时写出被替换掉的模型原文，用来判断没带引用的原因")
    void reportsDiscardedReply() {
        var uncited = new EvalOutcome(CASH, List.of(new Retrieved("收银.md", "备用金 800 元")), true, "UNCITED",
                "知识库里没有找到能回答这个问题的可靠依据", List.of(), 0.99, 100, 400, 1000)
                .withDiscardedReply("开班备用金为 800 元（资料1）。\n|其中硬币不少于 100 元");

        var report = EvalReport.of(List.of(uncited), Set.of());

        assertThat(report.failures())
                .containsExactly("备用金：误拒（UNCITED），模型原文：开班备用金为 800 元（资料1）。 ／其中硬币不少于 100 元");
        assertThat(report.toMarkdown()).contains("| 被替换的原文：开班备用金为 800 元（资料1）。 ／其中硬币不少于 100 元 |");
    }

    @Test
    @DisplayName("模型服务不可用的题不计入指标，在报告里列出；超过 5% 时结果不可信")
    void excludesUnavailableCases() {
        var answered = new EvalOutcome(SHELF, List.of(new Retrieved("陈列.md", "放在 03 号货架")), false, null,
                "在 03 号货架[1]", List.of("陈列.md"), 0.9, 100, 400, 1000);

        var report = EvalReport.of(List.of(answered), Set.of(), List.of("备用金"));

        assertThat(report.cases()).isEqualTo(1);
        assertThat(report.falseRefusalRate()).isZero();
        assertThat(report.unavailable()).containsExactly("备用金");
        assertThat(report.credible()).isFalse();
        assertThat(report.toMarkdown()).contains("模型服务不可用、未计入指标的题 1 道：备用金");
        assertThat(EvalReport.of(List.of(answered), Set.of()).credible()).isTrue();
    }
}
