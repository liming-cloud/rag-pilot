package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.application.ModelUnavailableException;
import java.util.Optional;

/** 选工具端口（D-059）：用回答模型做一次受限调用，输出要调的工具和参数（结构化输出）。 */
public interface ToolSelection {

    /**
     * @return 模型选的工具和参数；模型认为不用查数据，或输出不是约定的格式时为空
     * @throws ModelUnavailableException 模型不可用
     */
    Optional<ToolChoice> select(String systemPrompt, String userMessage);
}
