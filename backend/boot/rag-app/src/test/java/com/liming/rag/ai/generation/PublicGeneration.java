package com.liming.rag.ai.generation;

import com.liming.rag.ai.TextGeneration;

/** 违规样例：实现了根包能力接口的类是 public，其他模块就能绕过接口直接 new 它。 */
public class PublicGeneration implements TextGeneration {

    @Override
    public String complete(String systemPrompt, String userMessage) {
        return "";
    }
}
