package com.liming.rag.evaluation.infrastructure.ai;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.evaluation.api.AnswerJudge;
import com.liming.rag.evaluation.api.JudgeUnavailableException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 用模型做答案评判（#145：评判规则在这里，模型调用经 rag-ai）。温度为 0，要求最后输出两行：结论和理由。
 * 不用结构化输出：要兼容先输出思考过程的模型，提示词和解析已经过评判自检（JudgeProbeRun）校准。
 * 兼容先输出思考过程的模型：去掉 &lt;think&gt; 段，取最后一个“结论”。
 */
public final class ModelAnswerJudge implements AnswerJudge {

    static final String SYSTEM = """
            你是问答系统的评测员。对照参考答案，判断系统回答有没有答对问题。
            判断步骤：
            1. 从参考答案里列出回答这个问题必需的要点（问题没问到的细节不算要点）。
            2. 逐条检查系统回答是否包含每个要点，并且事实一致。说法不同、顺序不同、分点列出都算包含。
            3. 系统回答比参考答案多出的内容一律不扣分，除非它与参考答案明确矛盾。不要因为“参考答案没提到”而扣分。
            4. 要点全部包含且没有矛盾，判“正确”；包含一部分要点，或有个别要点与参考答案矛盾，判“部分正确”；
               要点基本都没答到、答非所问，或说找不到依据，判“错误”。
            5. 回答里的 [1] 这类引用编号忽略。不要用你自己的知识补充或纠正参考答案。
            最后只输出两行：
            结论：正确 / 部分正确 / 错误
            理由：一句话，说明缺了或答错了哪个要点
            """;

    /** 先输出思考过程的模型要多留输出长度，思考内容不计入结论。 */
    static final int MAX_TOKENS = 2048;

    private static final Pattern VERDICT = Pattern.compile("结论\\s*[:：]\\s*(.+)");
    private static final Pattern REASON = Pattern.compile("理由\\s*[:：]\\s*(.+)");
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");

    private final TextGeneration generation;

    ModelAnswerJudge(TextGeneration generation) {
        this.generation = generation;
    }

    /**
     * 调用 OpenAI 兼容接口上的评判模型。
     *
     * @param apiKey 只从环境变量读取，不进日志
     */
    public static AnswerJudge openAiCompatible(String baseUrl, String apiKey, String model, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置评判模型密钥：请设置环境变量 EVAL_JUDGE_API_KEY 或 MODEL_API_KEY");
        }
        return new ModelAnswerJudge(TextGeneration.fixed(baseUrl, apiKey, model, timeout, MAX_TOKENS));
    }

    @Override
    public Judgement judge(String question, String referenceAnswer, String answer) {
        var user = "问题：" + question + "\n\n参考答案：" + referenceAnswer + "\n\n系统回答：" + answer;
        try {
            return parse(generation.complete(SYSTEM, user));
        } catch (ModelCallException e) {
            throw new JudgeUnavailableException("答案评判失败：评判模型服务暂时不可用，请检查 EVAL_JUDGE_BASE_URL 和 EVAL_JUDGE_MODEL", e);
        }
    }

    /** 看不懂的输出判为错误并保留原文，便于人工复核时发现评判模型的问题。 */
    static Judgement parse(String output) {
        var text = THINKING.matcher(output == null ? "" : output).replaceAll("").strip();
        var verdict = last(VERDICT, text);
        var reason = last(REASON, text);
        var reasonText = reason == null ? "" : reason;
        if (verdict != null) {
            if (verdict.startsWith("部分正确")) {
                return new Judgement(Verdict.PARTIAL, reasonText);
            }
            if (verdict.startsWith("错误")) {
                return new Judgement(Verdict.WRONG, reasonText);
            }
            if (verdict.startsWith("正确")) {
                return new Judgement(Verdict.CORRECT, reasonText);
            }
        }
        return new Judgement(Verdict.WRONG, "看不懂评判输出：" + text);
    }

    /** 最后一次匹配的第一组，去掉首尾空白；没有匹配时为 null。 */
    private static @Nullable String last(Pattern pattern, String text) {
        String found = null;
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            found = matcher.group(1).strip();
        }
        return found;
    }
}
