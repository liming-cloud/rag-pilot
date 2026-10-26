package com.liming.rag.ai;

/** 对话模型：给定系统提示和用户消息，返回完整回复文本。 */
public interface ChatGateway {

    String complete(String systemPrompt, String userMessage);
}
