package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.application.ModelUnavailableException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 回答生成端口（D-054）：用回答模型流式生成。 */
public interface AnswerGeneration {

    /**
     * 每收到一段文字就交给 {@code onDelta}，结束后返回完整回复；{@code stop} 完成后不再等后续文字，返回已生成的部分。
     *
     * @throws ModelUnavailableException 模型不可用
     */
    String stream(String systemPrompt, String userMessage, Consumer<String> onDelta, CompletionStage<?> stop);
}
