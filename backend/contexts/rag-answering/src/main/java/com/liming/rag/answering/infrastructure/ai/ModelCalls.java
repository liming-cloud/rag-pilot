package com.liming.rag.answering.infrastructure.ai;

import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.answering.application.ModelUnavailableException;
import java.util.function.Supplier;

/** 调 rag-ai 时把 {@link ModelCallException} 转成本上下文的 {@link ModelUnavailableException}（D-054）。 */
final class ModelCalls {

    private ModelCalls() {
    }

    static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
    }
}
