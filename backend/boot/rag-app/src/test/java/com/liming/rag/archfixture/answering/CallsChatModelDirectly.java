package com.liming.rag.archfixture.answering;

import org.springframework.ai.chat.model.ChatModel;

/** 违规样例：rag-ai 之外直接调用模型。 */
public class CallsChatModelDirectly {

    String ask(ChatModel model) {
        return model.call("你好");
    }
}
