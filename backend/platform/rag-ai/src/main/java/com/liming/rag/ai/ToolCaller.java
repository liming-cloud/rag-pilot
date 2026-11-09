package com.liming.rag.ai;

import com.liming.rag.ai.tool.ToolCall;
import com.liming.rag.ai.tool.ToolCallException;
import com.liming.rag.ai.tool.ToolDefinition;
import com.liming.rag.ai.tool.ToolResult;
import java.util.List;

/**
 * 工具调用能力（D-058）：经 MCP 调用客户系统开放的只读工具。每次调用签发一个 ≤ 60 秒、只用于该工具的令牌，
 * 超时 10 秒，超时和 502/503/504 重试 1 次，其余失败不重试。失败统一抛 {@link ToolCallException}。
 * 数据范围的判定、调用前后的校验、审计不在这里，由调用方（上下文）负责。
 *
 * <p>用法：{@code call(new ToolCall(端点, 工具名, 参数, 用户标识, 数据范围…))} 取结果；{@code list(端点)} 列出工具。
 */
public interface ToolCaller {

    ToolResult call(ToolCall call);

    /** 列出连接上的工具（{@code tools/list}），用服务令牌，不带数据范围、不返回业务数据；超时和重试同 {@link #call}。 */
    List<ToolDefinition> list(String endpoint);
}
