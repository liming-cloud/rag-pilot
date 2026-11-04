package com.liming.rag.ai;

import java.time.Duration;

/**
 * 答案评判：对照参考答案判断回答是否正确。只在评测里用，不注册为 bean，不进入问答链路。
 *
 * <p>评判模型可以替换：默认用 OpenAI 兼容接口上的通用模型，也可以指向自己部署的专用评判模型
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
        return new OpenAiCompatibleJudge(baseUrl, apiKey, model, timeout);
    }
}
