package com.liming.rag.evaluation.api;

/**
 * 答案评判：对照参考答案判断回答是否正确。只在评测里用，不注册为 bean，不进入问答链路。
 *
 * <p>评判规则（提示词、三档结论、输出解析）和模型调用在 {@code infrastructure/ai} 的 {@code ModelAnswerJudge}
 * （#145、D-054）。评判模型可以替换：默认用 OpenAI 兼容接口上的通用模型，也可以指向自己部署的专用评判模型
 * （如 CompassJudger），只要它提供 OpenAI 兼容接口。见 D-024。
 */
public interface AnswerJudge {

    enum Verdict {
        CORRECT, PARTIAL, WRONG
    }

    record Judgement(Verdict verdict, String reason) {
    }

    /**
     * @throws JudgeUnavailableException 评判模型调用失败
     */
    Judgement judge(String question, String referenceAnswer, String answer);
}
