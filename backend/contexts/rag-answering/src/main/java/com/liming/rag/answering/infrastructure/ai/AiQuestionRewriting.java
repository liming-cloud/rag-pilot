package com.liming.rag.answering.infrastructure.ai;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.QueryRewriting;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.ask.QuestionRewriting;
import org.springframework.stereotype.Component;

/** 用 rag-ai 的 {@link QueryRewriting} 实现问题改写端口（D-054），输出由它限制在一句话。 */
@Component
class AiQuestionRewriting implements QuestionRewriting {

    private final QueryRewriting rewriting;

    AiQuestionRewriting(QueryRewriting rewriting) {
        this.rewriting = rewriting;
    }

    @Override
    public String rewrite(String instructions, String question) {
        try {
            return rewriting.rewrite(instructions, question);
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
    }
}
