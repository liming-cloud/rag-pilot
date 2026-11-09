package com.liming.rag.ai.tool;

import org.springframework.modulith.NamedInterface;

/**
 * 客户系统 {@code tools/list} 返回的一个工具，给模型选工具、填参数用。工具注解（如只读）不在这里：
 * 是否只读、能不能调由调用方的授权清单决定（D-058）。
 *
 * @param title 显示名，客户没给时为空串
 * @param inputSchema 参数的 JSON Schema（JSON 文本）
 */
@NamedInterface("tool")
public record ToolDefinition(String name, String title, String description, String inputSchema) {
}
