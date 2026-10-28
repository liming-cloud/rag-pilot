package com.liming.rag.ai;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 用 OpenAI 兼容的对话接口做答案评判。温度为 0，只要两行输出：结论和理由。 */
final class OpenAiCompatibleJudge implements AnswerJudge {

    static final String SYSTEM = """
            你是问答系统的评测员。对照参考答案，判断系统回答是否正确回答了问题。
            规则：
            1. 只看事实是否与参考答案一致，不看措辞、长短和格式；回答里的 [1] 这类引用编号忽略。
            2. 参考答案的要点都答对了，判为“正确”；只答对一部分要点，或答对了但夹带与参考答案矛盾的内容，判为“部分正确”；
               要点答错、答非所问或说找不到依据，判为“错误”。
            3. 不要用你自己的知识补充或纠正参考答案。
            只输出两行：
            结论：正确 / 部分正确 / 错误
            理由：一句话
            """;

    private static final Pattern VERDICT = Pattern.compile("结论\\s*[:：]\\s*(.+)");
    private static final Pattern REASON = Pattern.compile("理由\\s*[:：]\\s*(.+)");

    private final ChatModel model;

    OpenAiCompatibleJudge(String baseUrl, String apiKey, String model, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置评判模型密钥：请设置环境变量 EVAL_JUDGE_API_KEY 或 MODEL_API_KEY");
        }
        var options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(0.0)
                .maxTokens(200)
                .timeout(timeout)
                .maxRetries(1)
                .build();
        this.model = OpenAiChatModel.builder().options(options).build();
    }

    @Override
    public Judgement judge(String question, String referenceAnswer, String answer) {
        var user = "问题：" + question + "\n\n参考答案：" + referenceAnswer + "\n\n系统回答：" + answer;
        try {
            var response = model.call(new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(user))));
            return parse(response.getResult().getOutput().getText());
        } catch (RuntimeException e) {
            throw new ModelCallException("答案评判失败：评判模型服务暂时不可用，请检查 EVAL_JUDGE_BASE_URL 和 EVAL_JUDGE_MODEL", e);
        }
    }

    /** 看不懂的输出判为错误并保留原文，便于人工复核时发现评判模型的问题。 */
    static Judgement parse(String output) {
        var text = output == null ? "" : output.strip();
        var verdict = VERDICT.matcher(text);
        var reason = REASON.matcher(text);
        var reasonText = reason.find() ? reason.group(1).strip() : "";
        if (verdict.find()) {
            var value = verdict.group(1);
            if (value.startsWith("部分正确")) {
                return new Judgement(Verdict.PARTIAL, reasonText);
            }
            if (value.startsWith("错误")) {
                return new Judgement(Verdict.WRONG, reasonText);
            }
            if (value.startsWith("正确")) {
                return new Judgement(Verdict.CORRECT, reasonText);
            }
        }
        return new Judgement(Verdict.WRONG, "看不懂评判输出：" + text);
    }
}
