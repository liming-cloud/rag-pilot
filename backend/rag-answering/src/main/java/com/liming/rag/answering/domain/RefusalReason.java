package com.liming.rag.answering.domain;

/** 为什么拒答（三道关口，见 {@code AnswerService}）。存在回答记录里，管理端按它筛选和统计。 */
public enum RefusalReason {
    /** 指定知识库里没有检索到任何内容。 */
    NO_EVIDENCE,
    /** 检索到的内容与问题相关度都低于阈值。 */
    LOW_RELEVANCE,
    /** 模型判断资料不足以回答。 */
    MODEL_DECLINED,
    /** 模型给出的回答没有可核对的引用。 */
    UNCITED
}
