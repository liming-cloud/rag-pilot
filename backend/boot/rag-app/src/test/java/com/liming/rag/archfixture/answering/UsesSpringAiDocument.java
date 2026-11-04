package com.liming.rag.archfixture.answering;

import org.springframework.ai.document.Document;

/** 违规样例（#145）：上下文直接用 Spring AI 的类型（这里不是模型调用，只是它的文档类型），应经 rag-ai 的能力接口。 */
public class UsesSpringAiDocument {

    String text(Document document) {
        return document.getText();
    }
}
