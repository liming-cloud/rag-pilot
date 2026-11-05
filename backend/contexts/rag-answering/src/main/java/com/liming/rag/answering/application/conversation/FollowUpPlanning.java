package com.liming.rag.answering.application.conversation;

import com.liming.rag.answering.application.ModelUnavailableException;

/** 追问的检索规划端口（D-054）：用回答模型生成一小段输出。 */
public interface FollowUpPlanning {

    /**
     * @param maxTokens 最多生成多少 token
     * @throws ModelUnavailableException 模型不可用
     */
    String plan(String systemPrompt, String userMessage, int maxTokens);
}
