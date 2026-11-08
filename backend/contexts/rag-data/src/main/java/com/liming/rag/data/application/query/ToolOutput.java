package com.liming.rag.data.application.query;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 校验工具返回的结构（规范第 5 节）：{@code data} 是对象数组，{@code source} 有 {@code system}、{@code dataset}，
 * {@code query} 是对象，{@code asOf}、{@code queriedAt} 是带时区的时间，{@code truncated} 是布尔值。
 * 不符合就整份不用（不挑能用的部分），按协议错误处理。只校验我方要用的约定，不做完整的 JSON Schema 校验。
 */
record ToolOutput(List<Map<String, Object>> rows, String system, String dataset, Map<String, Object> query,
        OffsetDateTime asOf, OffsetDateTime queriedAt, boolean truncated) {

    /** 返回不符合约定时为 null。 */
    static @Nullable ToolOutput parse(Map<String, Object> content) {
        try {
            if (!(content.get("data") instanceof List<?> data) || !(content.get("source") instanceof Map<?, ?> source)
                    || !(source.get("system") instanceof String system) || system.isBlank()
                    || !(source.get("dataset") instanceof String dataset) || dataset.isBlank()
                    || !(content.get("query") instanceof Map<?, ?> query)
                    || !(content.get("asOf") instanceof String asOf)
                    || !(content.get("queriedAt") instanceof String queriedAt)
                    || !(content.get("truncated") instanceof Boolean truncated)) {
                return null;
            }
            var rows = new ArrayList<Map<String, Object>>();
            for (var row : data) {
                if (!(row instanceof Map<?, ?> map)) {
                    return null;
                }
                rows.add(stringKeys(map));
            }
            return new ToolOutput(rows, system, dataset, stringKeys(query), OffsetDateTime.parse(asOf),
                    OffsetDateTime.parse(queriedAt), truncated);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
