package com.liming.rag.evaluation.api;

import com.liming.rag.evaluation.api.EvalCase.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 评测指标，口径与 README 的验收指标一致：
 *
 * <ul>
 *   <li>Recall@10：可回答题中，前 10 条检索结果里有来自预期文档、且含答案关键词的分块的比例。</li>
 *   <li>引用正确率：可回答且未拒答的题中，第一条引用来自预期文档、且回答含答案关键词的比例。</li>
 *   <li>拒答正确率：应拒答题中实际拒答的比例；误拒率：可回答题中被拒答的比例。</li>
 *   <li>越权泄漏：检索结果或引用里出现无权访问文档的题数，必须为 0。</li>
 *   <li>首字延迟：流式问答从发出请求到收到第一个事件（第一段文字，或拒答结果）的时间。</li>
 * </ul>
 */
public record EvalReport(int cases, double recallAt10, double citationAccuracy, double refusalAccuracy,
        double falseRefusalRate, int leaks, long searchP95Millis, long firstTokenP95Millis, long askP95Millis, List<String> failures,
        List<EvalOutcome> outcomes) {

    public static EvalReport of(List<EvalOutcome> outcomes, Set<String> restrictedDocuments) {
        var answerable = outcomes.stream().filter(o -> o.evalCase().kind() == Kind.ANSWERABLE).toList();
        var unanswerable = outcomes.stream().filter(o -> o.evalCase().kind() == Kind.UNANSWERABLE).toList();
        var answered = answerable.stream().filter(o -> !o.refused()).toList();
        var failures = new ArrayList<String>();

        Predicate<EvalOutcome> recalled = o -> o.retrieved().stream().limit(10).anyMatch(r ->
                r.document().equals(o.evalCase().expectedDocument()) && o.evalCase().matches(r.content()));
        Predicate<EvalOutcome> citedCorrectly = o -> !o.citedDocuments().isEmpty()
                && o.citedDocuments().getFirst().equals(o.evalCase().expectedDocument())
                && o.evalCase().matches(o.answer());
        Predicate<EvalOutcome> leaked = o -> o.retrieved().stream().anyMatch(r -> restrictedDocuments.contains(r.document()))
                || o.citedDocuments().stream().anyMatch(restrictedDocuments::contains);

        for (var o : answerable) {
            if (!recalled.test(o)) {
                failures.add(o.evalCase().id() + "：前 10 条没有召回预期分块");
            }
            if (o.refused()) {
                failures.add(o.evalCase().id() + "：误拒（" + o.refusalReason() + "）");
            } else if (!citedCorrectly.test(o)) {
                failures.add(o.evalCase().id() + "：引用或答案不对，引用了 " + o.citedDocuments());
            }
        }
        for (var o : unanswerable) {
            if (!o.refused()) {
                failures.add(o.evalCase().id() + "：应拒答却回答了");
            }
        }
        int leaks = 0;
        for (var o : outcomes) {
            if (leaked.test(o)) {
                leaks++;
                failures.add(o.evalCase().id() + "：越权泄漏");
            }
        }
        return new EvalReport(outcomes.size(),
                ratio(answerable, recalled),
                ratio(answered, citedCorrectly),
                ratio(unanswerable, EvalOutcome::refused),
                ratio(answerable, EvalOutcome::refused),
                leaks,
                p95(outcomes.stream().map(EvalOutcome::searchMillis).toList()),
                p95(outcomes.stream().map(EvalOutcome::firstEventMillis).toList()),
                p95(outcomes.stream().map(EvalOutcome::askMillis).toList()),
                List.copyOf(failures),
                List.copyOf(outcomes));
    }

    private static double ratio(List<EvalOutcome> outcomes, Predicate<EvalOutcome> predicate) {
        if (outcomes.isEmpty()) {
            return Double.NaN;
        }
        return (double) outcomes.stream().filter(predicate).count() / outcomes.size();
    }

    /** 最近秩法的 p95。 */
    static long p95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        var sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    /** CI 运行摘要用的 Markdown 报告。 */
    public String toMarkdown() {
        var text = new StringBuilder("## 评测结果\n\n");
        text.append("共 ").append(cases).append(" 题。目标值见 README。\n\n");
        text.append("| 指标 | 结果 | 目标 |\n| --- | --- | --- |\n");
        row(text, "检索召回 Recall@10", percent(recallAt10), "≥ 85%");
        row(text, "引用正确率", percent(citationAccuracy), "≥ 90%");
        row(text, "拒答正确率", percent(refusalAccuracy), "≥ 90%");
        row(text, "误拒率", percent(falseRefusalRate), "越低越好");
        row(text, "越权泄漏", String.valueOf(leaks), "0");
        row(text, "检索延迟 p95", searchP95Millis + " ms", "< 800 ms");
        row(text, "首字延迟 p95（流式）", firstTokenP95Millis + " ms", "< 3 s");
        row(text, "整段回答 p95（流式）", askP95Millis + " ms", "—");
        text.append("\n### 重排最高分分布（用于校准拒答门槛）\n\n");
        text.append("| 题型 | 最低 | 中位 | 最高 |\n| --- | --- | --- | --- |\n");
        for (var kind : Kind.values()) {
            var scores = outcomes.stream()
                    .filter(o -> o.evalCase().kind() == kind)
                    .map(EvalOutcome::topRelevance)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (!scores.isEmpty()) {
                row(text, kind == Kind.ANSWERABLE ? "可回答" : "应拒答", fmt(scores.getFirst()),
                        fmt(scores.get(scores.size() / 2)), fmt(scores.getLast()));
            }
        }
        text.append("\n### 未通过的题\n\n");
        if (failures.isEmpty()) {
            text.append("无。\n");
        }
        failures.forEach(failure -> text.append("- ").append(failure).append('\n'));
        text.append("\n<details><summary>逐题明细</summary>\n\n| 题 | 拒答 | 最高分 | 引用 | 回答 |\n| --- | --- | --- | --- | --- |\n");
        for (var o : outcomes) {
            row(text, o.evalCase().id(), o.refused() ? "是（" + o.refusalReason() + "）" : "否",
                    o.topRelevance() == null ? "-" : fmt(o.topRelevance()), String.join("、", o.citedDocuments()),
                    o.answer() == null ? "" : o.answer().replace("\n", " ").replace("|", "／"));
        }
        return text.append("\n</details>\n").toString();
    }

    private static void row(StringBuilder text, String... cells) {
        text.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
