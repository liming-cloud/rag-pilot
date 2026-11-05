package com.liming.rag.evaluation.api;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.TextGeneration;

import java.time.Duration;

/**
 * 答案评判：对照参考答案判断回答是否正确。只在评测里用，不注册为 bean，不进入问答链路。
 *
 * <p>评判规则（提示词、三档结论、输出解析）是评测的策略，在这里；模型调用经 rag-ai 的 {@link TextGeneration}（#145）。
 * 评判模型可以替换：默认用 OpenAI 兼容接口上的通用模型，也可以指向自己部署的专用评判模型
 * （如 CompassJudger），只要它提供 OpenAI 兼容接口。见 DECISIONS.md D-024。
 */
public interface AnswerJudge {

    enum Verdict {
        CORRECT, PARTIAL, WRONG
    }

    record Judgement(Verdict verdict, String reason) {
    }

    /**
     * @throws ModelCallException 评判模型调用失败
     */
    Judgement judge(String question, String referenceAnswer, String answer);

    /**
     * 调用 OpenAI 兼容接口上的评判模型。
     *
     * @param apiKey 只从环境变量读取，不进日志
     */
    static AnswerJudge openAiCompatible(String baseUrl, String apiKey, String model, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置评判模型密钥：请设置环境变量 EVAL_JUDGE_API_KEY 或 MODEL_API_KEY");
        }
        return new ModelAnswerJudge(TextGeneration.fixed(baseUrl, apiKey, model, timeout, ModelAnswerJudge.MAX_TOKENS));
    }
}
