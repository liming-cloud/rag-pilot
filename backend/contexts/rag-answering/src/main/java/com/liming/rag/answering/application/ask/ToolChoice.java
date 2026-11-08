package com.liming.rag.answering.application.ask;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 模型选的工具和它填的参数。只是提议：工具必须在提问人可用的工具里，参数还要经数据范围判定。 */
public record ToolChoice(String tool, Map<String, Object> arguments) {

    public ToolChoice {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
