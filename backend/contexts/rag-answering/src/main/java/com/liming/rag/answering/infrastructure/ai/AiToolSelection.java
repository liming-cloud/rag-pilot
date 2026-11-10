package com.liming.rag.answering.infrastructure.ai;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.ask.ToolChoice;
import com.liming.rag.answering.application.ask.ToolSelection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用 rag-ai 的 {@link TextGeneration}（回答模型）选工具（D-059）：输出限制在一小段，只取其中第一个 JSON 对象
 * {@code {"tool":…,"arguments":{…}}}。不是这个格式、{@code tool} 为空时按不查数据处理；值为 null 的参数去掉。
 */
@Component
class AiToolSelection implements ToolSelection {

    private static final int MAX_TOKENS = 300;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final TextGeneration generation;

    AiToolSelection(TextGeneration generation) {
        this.generation = generation;
    }

    @Override
    public Optional<ToolChoice> select(String systemPrompt, String userMessage) {
        String raw;
        try {
            raw = generation.complete(systemPrompt, userMessage, new TextGeneration.Limits(MAX_TOKENS, List.of()));
        } catch (ModelCallException e) {
            throw new ModelUnavailableException(e.getMessage(), e);
        }
        return parse(raw);
    }

    static Optional<ToolChoice> parse(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            return Optional.empty();
        }
        try {
            var node = JSON.readTree(raw.substring(start, end + 1));
            var tool = node.path("tool");
            if (!tool.isString() || tool.asString().isBlank()) {
                return Optional.empty();
            }
            var arguments = node.path("arguments");
            if (!arguments.isMissingNode() && !arguments.isNull() && !arguments.isObject()) {
                return Optional.empty();
            }
            return Optional.of(new ToolChoice(tool.asString().strip(), values(arguments)));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /** 参数里值为 null 的项去掉；没写参数时为空表。 */
    private static Map<String, Object> values(JsonNode arguments) {
        var values = new LinkedHashMap<String, Object>();
        if (arguments.isObject()) {
            JSON.convertValue(arguments, OBJECT).forEach((key, value) -> {
                if (value != null) {
                    values.put(key, value);
                }
            });
        }
        return values;
    }
}
