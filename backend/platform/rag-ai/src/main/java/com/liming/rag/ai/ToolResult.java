package com.liming.rag.ai;

import java.util.Map;

/**
 * 工具返回的结构化结果（规范第 5 节：{@code data/source/query/asOf/queriedAt/truncated}），内容不在这里解释。
 *
 * @param content 工具的 {@code structuredContent}
 * @param tokenId 本次调用令牌的 {@code jti}，写进审计，与客户系统的日志对账
 */
public record ToolResult(Map<String, Object> content, String tokenId) {
}
