package com.liming.rag.answering.application.conversation;

import com.liming.rag.answering.application.ModelUnavailableException;

/** 轻量回复端口（D-054）：寒暄、会话标题这类轻量任务用单独配置的便宜模型（D-036），只要一行，遇换行就停。 */
public interface LightReplies {

    /**
     * @param maxTokens 最多生成多少 token
     * @throws ModelUnavailableException 模型不可用
     */
    String reply(String systemPrompt, String userMessage, int maxTokens);
}
