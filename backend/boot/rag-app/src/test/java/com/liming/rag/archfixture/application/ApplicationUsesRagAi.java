package com.liming.rag.archfixture.application;

import com.liming.rag.ai.TextGeneration;

/** 违规样例（D-054）：应用层直接用 rag-ai，应在 application 声明按用途的端口，由 infrastructure/ai 的适配器实现。 */
public class ApplicationUsesRagAi {

    private final TextGeneration generation;

    ApplicationUsesRagAi(TextGeneration generation) {
        this.generation = generation;
    }

    String answer(String question) {
        return generation.complete("只依据资料回答", question);
    }
}
