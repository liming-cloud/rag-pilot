package com.liming.rag.data.application.query;

import java.util.Map;

/**
 * 工具返回的 {@code structuredContent}（还没校验）和本次令牌的 {@code jti}。
 *
 * @param tokenId 写进审计，与客户系统的日志对账
 */
public record ToolReply(Map<String, Object> content, String tokenId) {
}
