package com.liming.rag.evaluation.api;

import com.liming.rag.evaluation.api.EvalCase.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 评测指标，口径与 README 的验收指标一致：
 *
 * <ul>
 *   <li>Recall@10：可回答题中，前 10 条检索结果里有来自预期文档（任一篇）、且含答案关键词的分块的比例。</li>
 *   <li>引用正确率：可回答且未拒答的题中，第一条引用来自预期文档、且回答含答案关键词的比例。</li>
 *   <li>答案评判：有参考答案的题由评判模型判为正确、部分正确或错误；只统计给出了回答的可回答题。</li>
 *   <li>拒答正确率：应拒答题中实际拒答的比例；误拒率：可回答题中被拒答的比例。</li>
 *   <li>越权泄漏：检索结果或引用里出现无权访问文档的题数，必须为 0。</li>
 *   <li>首字延迟：流式问答从发出请求到收到第一个事件（第一段文字，或拒答结果）的时间。</li>
 * </ul>
 *
 * @param failedCases 未通过的题（id），去重并保持出现顺序；多轮汇总用它区分稳定失败和偶发失败
 * @param unavailable 重试后模型服务仍不可用、没有结果的题（id），不计入任何指标
 */
public record EvalReport(int cases, double recallAt10, double citationAccuracy, double refusalAccuracy,
        double falseRefusalRate, int leaks, long searchP95Millis, long firstTokenP95Millis, long askP95Millis, List<String> failures,
        List<EvalOutcome> outcomes, List<String> failedCases, List<String> unavailable) {

    /** 服务不可用的题超过这个比例时，这次结果不可信。 */
    static final double MAX_UNAVAILABLE_SHARE = 0.05;

    public static EvalReport of(List<EvalOutcome> outcomes, Set<String> restrictedDocuments) {
        return of(outcomes, restrictedDocuments, List.of());
    }

    /**
     * @param unavailable 模型服务不可用、重试后仍没有结果的题；一道题碰上 503 不该让整次评测作废（#81）
     */
    public static EvalReport of(List<EvalOutcome> outcomes, Set<String> restrictedDocuments, List<String> unavailable) {
        var answerable = outcomes.stream().filter(o -> o.evalCase().kind() == Kind.ANSWERABLE).toList();
        var unanswerable = outcomes.stream().filter(o -> o.evalCase().kind() == Kind.UNANSWERABLE).toList();
        var answered = answerable.stream().filter(o -> !o.refused()).toList();
        var failures = new ArrayList<String>();
        var failedCases = new LinkedHashSet<String>();

        Predicate<EvalOutcome> recalled = EvalReport::recalled;
        Predicate<EvalOutcome> citedCorrectly = EvalReport::citedCorrectly;
        Predicate<EvalOutcome> leaked = o -> o.retrieved().stream().anyMatch(r -> restrictedDocuments.contains(r.document()))
                || o.citedDocuments().stream().anyMatch(restrictedDocuments::contains);

        for (var o : answerable) {
            if (!recalled.test(o)) {
                failures.add(o.evalCase().id() + "：前 10 条没有召回预期分块");
                failedCases.add(o.evalCase().id());
            }
            if (o.refused()) {
                failures.add(o.evalCase().id() + "：误拒（" + o.refusalReason() + "）"
                        + (o.discardedReply() == null ? "" : "，模型原文：" + cell(o.discardedReply())));
                failedCases.add(o.evalCase().id());
            } else if (!citedCorrectly.test(o)) {
                failures.add(o.evalCase().id() + "：引用或答案不对，引用了 " + o.citedDocuments());
                failedCases.add(o.evalCase().id());
            }
            if (!o.refused() && o.judgement() != null && !CORRECT.equals(o.judgement().verdict())) {
                failures.add(o.evalCase().id() + "：答案" + verdictLabel(o.judgement().verdict()) + "（"
                        + o.judgement().reason() + "）");
                failedCases.add(o.evalCase().id());
            }
        }
        for (var o : unanswerable) {
            if (!o.refused()) {
                failures.add(o.evalCase().id() + "：应拒答却回答了");
                failedCases.add(o.evalCase().id());
            }
        }
        int leaks = 0;
        for (var o : outcomes) {
            if (leaked.test(o)) {
                leaks++;
                failures.add(o.evalCase().id() + "：越权泄漏");
                failedCases.add(o.evalCase().id());
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
                List.copyOf(outcomes),
                List.copyOf(failedCases),
                List.copyOf(unavailable));
    }

    private static final String CORRECT = "CORRECT";

    /** 服务不可用的题不超过 5% 时，结果才可信。 */
    public boolean credible() {
        int total = cases + unavailable.size();
        return total > 0 && (double) unavailable.size() / total <= MAX_UNAVAILABLE_SHARE;
    }
    /** 首字延迟的参考线（D-030 起不作为验收硬指标），超过的题逐个列出。 */
    private static final long SLOW_FIRST_TOKEN_MILLIS = 3000;

    /** 前 10 条检索结果里有来自预期文档（任一篇）、且含答案关键词的分块。 */
    static boolean recalled(EvalOutcome o) {
        return o.retrieved().stream().limit(10).anyMatch(r ->
                o.evalCase().expectedDocuments().contains(r.document()) && o.evalCase().matches(r.content()));
    }

    /** 每篇预期文档都有分块进了前 10，衡量多篇新闻综合回答的题。 */
    static boolean recalledAll(EvalOutcome o) {
        var top = o.retrieved().stream().limit(10).map(EvalOutcome.Retrieved::document).toList();
        return top.containsAll(o.evalCase().expectedDocuments());
    }

    /** 第一条引用来自预期文档，且回答含答案关键词。 */
    static boolean citedCorrectly(EvalOutcome o) {
        return !o.citedDocuments().isEmpty()
                && o.evalCase().expectedDocuments().contains(o.citedDocuments().getFirst())
                && o.evalCase().matches(o.answer());
    }

    private static String verdictLabel(String verdict) {
        return switch (verdict) {
            case CORRECT -> "正确";
            case "PARTIAL" -> "部分正确";
            default -> "错误";
        };
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
        if (!unavailable.isEmpty()) {
            text.append("模型服务不可用、未计入指标的题 ").append(unavailable.size()).append(" 道：")
                    .append(String.join("、", unavailable))
                    .append(credible() ? "。\n\n" : "。超过 5%，这次结果不可信。\n\n");
        }
        text.append("| 指标 | 结果 | 目标 |\n| --- | --- | --- |\n");
        row(text, "检索召回 Recall@10", percent(recallAt10), "≥ 85%");
        row(text, "引用正确率", percent(citationAccuracy), "≥ 90%");
        row(text, "拒答正确率", percent(refusalAccuracy), "≥ 90%");
        row(text, "误拒率", percent(falseRefusalRate), "越低越好");
        var multiDocument = outcomes.stream()
                .filter(o -> o.evalCase().kind() == Kind.ANSWERABLE && o.evalCase().expectedDocuments().size() > 1)
                .toList();
        if (!multiDocument.isEmpty()) {
            row(text, "多篇原文全部召回", percent(ratio(multiDocument, EvalReport::recalledAll)), "—");
        }
        row(text, "越权泄漏", String.valueOf(leaks), "0");
        row(text, "检索延迟 p95", searchP95Millis + " ms", "< 800 ms");
        row(text, "首字延迟 p95（流式）", firstTokenP95Millis + " ms", "参考（D-030）");
        row(text, "整段回答 p95（流式）", askP95Millis + " ms", "—");
        text.append("\n### 首字延迟拆分（服务端各阶段 p95）\n\n");
        text.append("首字延迟 ≈ 检索 + 重排 + 模型首字 + 网络。\n\n| 阶段 | p95 |\n| --- | --- |\n");
        row(text, "检索（问题向量化 + 混合检索）", stageP95(EvalOutcome.Stages::retrievalMillis));
        row(text, "重排", stageP95(EvalOutcome.Stages::rerankMillis));
        row(text, "模型首字（从开始生成算起）", stageP95(EvalOutcome.Stages::modelFirstTokenMillis));
        row(text, "低分改写合计（改写 + 重新检索、重排）", stageP95(EvalOutcome.Stages::rewriteMillis));
        row(text, "　改写·模型", rewriteP95(EvalOutcome.Rewrite::modelMillis));
        row(text, "　改写·重新检索", rewriteP95(EvalOutcome.Rewrite::retrievalMillis));
        row(text, "　改写·重新重排", rewriteP95(EvalOutcome.Rewrite::rerankMillis));
        // p95 只看得出尾部有多慢，看不出是几道题偶尔卡住还是整体都慢，所以把超标的题逐个列出来。
        var slow = outcomes.stream()
                .filter(o -> o.firstEventMillis() > SLOW_FIRST_TOKEN_MILLIS)
                .sorted(Comparator.comparingLong(EvalOutcome::firstEventMillis).reversed())
                .map(o -> o.evalCase().id() + " " + o.firstEventMillis() + " ms"
                        + (o.stages() == null || o.stages().modelFirstTokenMillis() == null ? ""
                                : "（模型首字 " + o.stages().modelFirstTokenMillis() + " ms）"))
                .toList();
        text.append("\n首字超过 3 s 的题 ").append(slow.size()).append(" / ").append(outcomes.size())
                .append(slow.isEmpty() ? "。" : "：" + String.join("、", slow)).append('\n');
        var attempted = outcomes.stream().filter(o -> o.rewrite() != null).toList();
        var rewritten = attempted.stream().filter(o -> "REWRITTEN".equals(o.rewrite().outcome())).toList();
        text.append("\n### 低分改写\n\n");
        text.append("重排最高分低于门槛时改写问题再检索一次。走到低分改写 ").append(attempted.size()).append(" 题：")
                .append("低于下限跳过 ").append(countOutcome(attempted, "SKIPPED_BELOW_FLOOR")).append(" 题，")
                .append("超时 ").append(countOutcome(attempted, "TIMED_OUT")).append(" 题，")
                .append("没给出有效改写 ").append(countOutcome(attempted, "NO_CHANGE")).append(" 题，")
                .append("已改写 ").append(rewritten.size()).append(" 题；")
                .append("可回答题救回 ").append(rewritten.stream()
                        .filter(o -> o.evalCase().kind() == Kind.ANSWERABLE && citedCorrectly(o)).count()).append(" 题，")
                .append("应拒答题被改写后回答了 ").append(rewritten.stream()
                        .filter(o -> o.evalCase().kind() == Kind.UNANSWERABLE && !o.refused()).count())
                .append(" 题。改写输出最长 ").append(attempted.stream().mapToInt(o -> o.rewrite().outputChars()).max()
                        .orElse(0)).append(" 字。“救回”指改写后给出了正确回答。\n");
        var judged = outcomes.stream().filter(o -> !o.refused() && o.judgement() != null).toList();
        if (!judged.isEmpty()) {
            text.append("\n### 答案评判\n\n");
            text.append("评判模型对照参考答案判断回答，只统计给出了回答的题。答案评判 ").append(judged.size()).append(" 题：")
                    .append(verdictCount(judged, CORRECT, "正确")).append("，")
                    .append(verdictCount(judged, "PARTIAL", "部分正确")).append("，")
                    .append(verdictCount(judged, "WRONG", "错误")).append("。\n");
        }
        text.append("\n### 按问法统计（可回答题）\n\n| 问法 | 题数 | 召回 | 引用正确 | 误拒 |\n| --- | --- | --- | --- | --- |\n");
        var byStyle = outcomes.stream()
                .filter(o -> o.evalCase().kind() == Kind.ANSWERABLE)
                .collect(Collectors.groupingBy(o -> o.evalCase().style(), LinkedHashMap::new, Collectors.toList()));
        byStyle.forEach((style, group) -> row(text, style, String.valueOf(group.size()),
                percent(ratio(group, EvalReport::recalled)),
                percent(ratio(group.stream().filter(o -> !o.refused()).toList(), EvalReport::citedCorrectly)),
                percent(ratio(group, EvalOutcome::refused))));
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
        text.append("\n<details><summary>逐题明细</summary>\n\n| 题 | 拒答 | 最高分 | 首字 | 改写为 | 引用 | 评判 | 回答 |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (var o : outcomes) {
            row(text, o.evalCase().id(), o.refused() ? "是（" + o.refusalReason() + "）" : "否",
                    o.topRelevance() == null ? "-" : fmt(o.topRelevance()), o.firstEventMillis() + " ms",
                    o.rewrittenQuestion() == null ? "" : cell(o.rewrittenQuestion()),
                    String.join("、", o.citedDocuments()),
                    o.judgement() == null ? "" : verdictLabel(o.judgement().verdict()),
                    o.discardedReply() != null ? "被替换的原文：" + cell(o.discardedReply())
                            : o.answer() == null ? "" : cell(o.answer()));
        }
        return text.append("\n</details>\n").toString();
    }

    private String stageP95(Function<EvalOutcome.Stages, Long> stage) {
        var values = outcomes.stream()
                .map(EvalOutcome::stages)
                .filter(Objects::nonNull)
                .map(stage)
                .filter(Objects::nonNull)
                .toList();
        return values.isEmpty() ? "-" : p95(values) + " ms";
    }

    private String rewriteP95(Function<EvalOutcome.Rewrite, Long> stage) {
        var values = outcomes.stream()
                .map(EvalOutcome::rewrite)
                .filter(Objects::nonNull)
                .map(stage)
                .filter(Objects::nonNull)
                .toList();
        return values.isEmpty() ? "-" : p95(values) + " ms";
    }

    private static String verdictCount(List<EvalOutcome> judged, String verdict, String label) {
        var count = judged.stream().filter(o -> verdict.equals(o.judgement().verdict())).count();
        return label + " " + count + " 题（" + percent((double) count / judged.size()) + "）";
    }

    private static long countOutcome(List<EvalOutcome> outcomes, String outcome) {
        return outcomes.stream().filter(o -> outcome.equals(o.rewrite().outcome())).count();
    }

    private static void row(StringBuilder text, String... cells) {
        text.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    /** 放进表格单元格或单行列表：换行变空格，竖线换成全角。 */
    private static String cell(String text) {
        return text.replace("\n", " ").replace("|", "／");
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
