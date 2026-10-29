package com.liming.rag.evaluation.api;

import com.liming.rag.evaluation.api.EvalCase.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * 同一份代码连续跑 N 轮评测的汇总。模型输出有波动，单轮结果判断不了改动是否有效（#81），
 * 所以看各项指标的均值和范围，并区分每轮都失败的题（稳定失败）和只在部分轮次失败的题（偶发失败）。
 *
 * <p>另附拒答门槛推算：用每题的重排最高分估计换一个门槛时的误拒率和拒答正确率，不必为每个门槛重跑。
 */
public record EvalRounds(List<EvalReport> rounds) {

    /** 推算用的候选门槛。 */
    static final List<Double> THRESHOLDS = List.of(0.02, 0.05, 0.1, 0.15, 0.2, 0.3, 0.5);

    public EvalRounds {
        if (rounds.isEmpty()) {
            throw new IllegalArgumentException("至少要有一轮评测");
        }
        rounds = List.copyOf(rounds);
    }

    public static EvalRounds of(List<EvalReport> rounds) {
        return new EvalRounds(rounds);
    }

    /** 一项指标在各轮的均值、最小、最大；没有对应题目的轮次不计入。 */
    public record Stat(double mean, double min, double max) {

        static Stat of(List<Double> values) {
            var present = values.stream().filter(value -> !Double.isNaN(value)).toList();
            if (present.isEmpty()) {
                return new Stat(Double.NaN, Double.NaN, Double.NaN);
            }
            return new Stat(present.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    present.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                    present.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
        }
    }

    /** 某个门槛下推算出的误拒率和拒答正确率（各轮的题合在一起算）。 */
    public record Estimate(double threshold, double falseRefusalRate, double refusalAccuracy) {
    }

    public Stat recallAt10() {
        return stat(EvalReport::recallAt10);
    }

    public Stat citationAccuracy() {
        return stat(EvalReport::citationAccuracy);
    }

    public Stat refusalAccuracy() {
        return stat(EvalReport::refusalAccuracy);
    }

    public Stat falseRefusalRate() {
        return stat(EvalReport::falseRefusalRate);
    }

    public Stat searchP95Millis() {
        return stat(report -> report.searchP95Millis());
    }

    public Stat firstTokenP95Millis() {
        return stat(report -> report.firstTokenP95Millis());
    }

    /** 各轮越权泄漏的合计，必须为 0。 */
    public int leaks() {
        return rounds.stream().mapToInt(EvalReport::leaks).sum();
    }

    /** 每一轮都失败的题。 */
    public List<String> stableFailures() {
        return failureCounts().entrySet().stream()
                .filter(entry -> entry.getValue() == rounds.size())
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /** 只在部分轮次失败的题。 */
    public List<String> flakyFailures() {
        return failureCounts().entrySet().stream()
                .filter(entry -> entry.getValue() < rounds.size())
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /**
     * 推算门槛为 {@code threshold} 时的指标。口径：已经回答的题，重排最高分低于门槛就算拒答；
     * 因低分（LOW_RELEVANCE）拒答的题，分数达到门槛就算回答；模型拒答、没有有效引用、没有检索结果的题按实际拒答。
     * 重排最高分是低分改写之后的分数，所以推算的是“改写照常进行”时的情况。
     */
    public static Estimate estimate(List<EvalReport> reports, double threshold) {
        var outcomes = reports.stream().flatMap(report -> report.outcomes().stream()).toList();
        var answerable = outcomes.stream().filter(o -> o.evalCase().kind() == Kind.ANSWERABLE).toList();
        var unanswerable = outcomes.stream().filter(o -> o.evalCase().kind() == Kind.UNANSWERABLE).toList();
        return new Estimate(threshold, share(answerable, o -> refusedAt(o, threshold)),
                share(unanswerable, o -> refusedAt(o, threshold)));
    }

    private static boolean refusedAt(EvalOutcome o, double threshold) {
        if (o.topRelevance() == null) {
            return true;
        }
        if (o.refused() && !"LOW_RELEVANCE".equals(o.refusalReason())) {
            return true;
        }
        return o.topRelevance() < threshold;
    }

    private static double share(List<EvalOutcome> outcomes, java.util.function.Predicate<EvalOutcome> predicate) {
        return outcomes.isEmpty() ? Double.NaN : (double) outcomes.stream().filter(predicate).count() / outcomes.size();
    }

    public String toMarkdown() {
        if (rounds.size() == 1) {
            return rounds.getFirst().toMarkdown() + thresholdSection();
        }
        var text = new StringBuilder("## 评测结果：多轮汇总\n\n");
        text.append("共 ").append(rounds.size()).append(" 轮，每轮 ").append(rounds.getFirst().cases())
                .append(" 题。同一份代码连续运行，看各项指标的均值和波动范围；目标值见 README。\n\n");
        text.append("| 指标 | 均值 | 最小 | 最大 | 目标 |\n| --- | --- | --- | --- | --- |\n");
        percentRow(text, "检索召回 Recall@10", recallAt10(), "≥ 85%");
        percentRow(text, "引用正确率", citationAccuracy(), "≥ 90%");
        percentRow(text, "拒答正确率", refusalAccuracy(), "≥ 90%");
        percentRow(text, "误拒率", falseRefusalRate(), "≤ 3%");
        row(text, "越权泄漏（各轮合计）", String.valueOf(leaks()), "", "", "0");
        millisRow(text, "检索延迟 p95", searchP95Millis(), "< 800 ms");
        millisRow(text, "首字延迟 p95（流式）", firstTokenP95Millis(), "参考（D-030）");

        text.append("\n### 每轮\n\n| 轮 | 召回 | 引用正确 | 拒答正确 | 误拒 | 越权 | 首字 p95 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < rounds.size(); i++) {
            var report = rounds.get(i);
            row(text, "第 " + (i + 1) + " 轮", percent(report.recallAt10()), percent(report.citationAccuracy()),
                    percent(report.refusalAccuracy()), percent(report.falseRefusalRate()),
                    String.valueOf(report.leaks()), report.firstTokenP95Millis() + " ms");
        }

        text.append("\n### 逐题稳定性\n\n");
        var stable = stableFailures();
        text.append("稳定失败（").append(rounds.size()).append(" / ").append(rounds.size()).append(" 轮）：")
                .append(stable.isEmpty() ? "无" : String.join("、", stable)).append("。\n\n");
        var counts = failureCounts();
        var flaky = flakyFailures().stream()
                .map(id -> id + "（" + counts.get(id) + " / " + rounds.size() + " 轮）")
                .toList();
        text.append("偶发失败：").append(flaky.isEmpty() ? "无" : String.join("、", flaky)).append("。\n");
        text.append(thresholdSection());
        for (int i = 0; i < rounds.size(); i++) {
            text.append("\n<details><summary>第 ").append(i + 1).append(" 轮明细</summary>\n\n")
                    .append(rounds.get(i).toMarkdown()).append("\n</details>\n");
        }
        return text.toString();
    }

    private String thresholdSection() {
        var text = new StringBuilder("\n### 拒答门槛推算\n\n");
        text.append("用每题的重排最高分推算换一个门槛时的结果（各轮合并）：已回答的题分数低于门槛算拒答，")
                .append("因低分拒答的题分数达到门槛算回答，模型拒答、没有引用的题按实际。只是估计，定门槛前要按选定的值实跑验证。\n\n");
        text.append("| 门槛 | 误拒率 | 拒答正确率 |\n| --- | --- | --- |\n");
        for (var threshold : THRESHOLDS) {
            var estimate = estimate(rounds, threshold);
            row(text, String.format(Locale.ROOT, "%.2f", threshold), percent(estimate.falseRefusalRate()),
                    percent(estimate.refusalAccuracy()));
        }
        return text.toString();
    }

    /** 各题失败的轮数，按首次出现的顺序。 */
    private LinkedHashMap<String, Integer> failureCounts() {
        var counts = new LinkedHashMap<String, Integer>();
        for (var report : rounds) {
            for (var id : report.failedCases()) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    private Stat stat(ToDoubleFunction<EvalReport> metric) {
        var values = new ArrayList<Double>();
        rounds.forEach(report -> values.add(metric.applyAsDouble(report)));
        return Stat.of(values);
    }

    private static void percentRow(StringBuilder text, String name, Stat stat, String target) {
        row(text, name, percent(stat.mean()), percent(stat.min()), percent(stat.max()), target);
    }

    private static void millisRow(StringBuilder text, String name, Stat stat, String target) {
        row(text, name, Math.round(stat.mean()) + " ms", Math.round(stat.min()) + " ms",
                Math.round(stat.max()) + " ms", target);
    }

    private static void row(StringBuilder text, String... cells) {
        text.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
