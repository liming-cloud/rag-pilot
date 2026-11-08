package com.liming.rag.ai;

import java.util.Map;

/**
 * 一次工具调用的请求。权限由调用方（上下文）先判定好，这里只负责带着提问人身份发出去。
 *
 * @param endpoint 连接名，对应 {@code rag.ai.tools.endpoints.<名>}
 * @param tool 工具名
 * @param arguments 工具参数（只放结构化条件）
 * @param subject 提问人的不透明用户标识，写进令牌的 {@code sub}
 * @param dataScope 调用方判定出的数据范围，写进令牌的 {@code data_scope}，如 {@code {"stores":["S001"]}}
 */
public record ToolCall(String endpoint, String tool, Map<String, Object> arguments, String subject,
        Map<String, Object> dataScope) {
}
