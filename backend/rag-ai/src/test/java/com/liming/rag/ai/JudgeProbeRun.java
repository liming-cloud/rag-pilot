package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评判自检：用结论事先知道的样本（scripts/eval/judge_probe.py 生成）检验评判模型准不准，报告写到 target/judge-probe.md。
 *
 * <p>默认构建不运行（标签 eval），需要 {@code EVAL_JUDGE_PROBE} 指向样本文件和 MODEL_API_KEY。
 * 评判模型的配置与 EvaluationRun 相同：{@code EVAL_JUDGE_BASE_URL}、{@code EVAL_JUDGE_MODEL}、{@code EVAL_JUDGE_API_KEY}。
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "EVAL_JUDGE_PROBE", matches = ".+")
class JudgeProbeRun {

    /** 同时评判的题数，免费额度限流较紧。 */
    private static final int PARALLEL = 4;

    private record Probe(String id, String kind, String question, String referenceAnswer, String answer,
            String expected) {
    }

    private record Result(Probe probe, AnswerJudge.Judgement judgement) {

        boolean agrees() {
            return judgement.verdict().name().equals(probe.expected());
        }
    }

    @Test
    @DisplayName("评判自检：各类样本上的准确率")
    void probe() throws IOException, InterruptedException, ExecutionException {
        List<Probe> probes = JsonMapper.builder().build().readValue(
                Files.readString(Path.of(System.getenv("EVAL_JUDGE_PROBE"))), new TypeReference<>() {
                });
        var model = env("EVAL_JUDGE_MODEL", "Qwen/Qwen2.5-7B-Instruct");
        var judge = AnswerJudge.openAiCompatible(env("EVAL_JUDGE_BASE_URL",
                        env("MODEL_BASE_URL", "https://api.siliconflow.cn/v1")),
                env("EVAL_JUDGE_API_KEY", System.getenv("MODEL_API_KEY")), model, Duration.ofSeconds(120));

        var permits = new Semaphore(PARALLEL);
        var results = new ArrayList<Result>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Result>>();
            for (var probe : probes) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return new Result(probe, judge.judge(probe.question(), probe.referenceAnswer(), probe.answer()));
                    } catch (ModelCallException e) {
                        var cause = e.getCause() == null ? "" : e.getCause().getMessage();
                        return new Result(probe, new AnswerJudge.Judgement(null, "评判调用失败：" + cause));
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (var future : futures) {
                results.add(future.get());
            }
        }
        publish(model, results);
        assertThat(results).as("评判调用全部失败，检查模型名和地址")
                .anyMatch(result -> result.judgement().verdict() != null);
    }

    private static void publish(String model, List<Result> results) throws IOException {
        var text = new StringBuilder("## 评判自检：").append(model).append("\n\n");
        var answered = results.stream().filter(result -> result.judgement().verdict() != null).toList();
        text.append(String.format(Locale.ROOT, "共 %d 题，评判成功 %d 题，结论一致 %s。%n%n", results.size(),
                answered.size(), percent(answered.stream().filter(Result::agrees).count(), answered.size())));
        text.append("| 样本 | 应判 | 题数 | 一致 | 判为正确 | 判为部分正确 | 判为错误 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        var byKind = new LinkedHashMap<String, List<Result>>();
        answered.forEach(result -> byKind.computeIfAbsent(result.probe().kind(), kind -> new ArrayList<>()).add(result));
        byKind.forEach((kind, group) -> text.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %d | %d | %d |%n",
                kind, group.getFirst().probe().expected(), group.size(),
                percent(group.stream().filter(Result::agrees).count(), group.size()),
                count(group, AnswerJudge.Verdict.CORRECT), count(group, AnswerJudge.Verdict.PARTIAL),
                count(group, AnswerJudge.Verdict.WRONG))));
        text.append("\n<details><summary>结论不一致的题</summary>\n\n");
        results.stream().filter(result -> result.judgement().verdict() == null || !result.agrees())
                .forEach(result -> text.append("- ").append(result.probe().id()).append("：应判 ")
                        .append(result.probe().expected()).append("，判为 ").append(result.judgement().verdict())
                        .append("（").append(result.judgement().reason().replace("\n", " ")).append("）\n"));
        text.append("\n</details>\n");

        var markdown = text.toString();
        Files.writeString(Path.of("target", "judge-probe.md"), markdown);
        var summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            Files.writeString(Path.of(summary), markdown, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.println(markdown);
    }

    private static long count(List<Result> group, AnswerJudge.Verdict verdict) {
        return group.stream().filter(result -> result.judgement().verdict() == verdict).count();
    }

    private static String percent(long part, int whole) {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
