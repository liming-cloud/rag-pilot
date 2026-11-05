package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.application.ModelUnavailableException;

/** 问题改写端口（D-054）：按 {@code instructions} 用回答模型把问题改写成一句话；改写规则是调用方的策略。 */
public interface QuestionRewriting {

    /**
     * @return 模型给出的改写，去掉首尾空白；模型什么也没给时为空串
     * @throws ModelUnavailableException 模型不可用
     */
    String rewrite(String instructions, String question);
}
