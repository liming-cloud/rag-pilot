package com.liming.rag.data.infrastructure.ai;

import com.liming.rag.ai.ToolCaller;
import com.liming.rag.ai.tool.ToolCall;
import com.liming.rag.ai.tool.ToolCallException;
import com.liming.rag.data.api.DataCatalog.DataTool;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.query.DataToolException;
import com.liming.rag.data.application.query.DataTools;
import com.liming.rag.data.application.query.ToolReply;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 经平台组件 rag-ai 调用客户的只读工具（D-058）：令牌的 {@code data_scope} 是 {@code {"stores":[…]}}。
 * rag-ai 的失败分类转成 {@link DataToolException}，工具报错时带上它的错误码；工具自己的说明前面加上“暂时查不到”，
 * 员工一看就知道没有数据（规范第 6 节）。
 */
@Component
class ToolCallerDataTools implements DataTools {

    private final ToolCaller tools;
    private final DataProperties properties;

    ToolCallerDataTools(ToolCaller tools, DataProperties properties) {
        this.tools = tools;
        this.properties = properties;
    }

    @Override
    public List<DataTool> list() {
        try {
            return tools.list(properties.endpoint()).stream()
                    .map(tool -> new DataTool(tool.name(), tool.title(), tool.description(), tool.inputSchema()))
                    .toList();
        } catch (ToolCallException e) {
            throw failed(e);
        }
    }

    @Override
    public ToolReply call(String tool, Map<String, Object> arguments, String subject, Set<String> units) {
        try {
            var result = tools.call(new ToolCall(properties.endpoint(), tool, arguments, subject,
                    Map.of("stores", units.stream().sorted().toList())));
            return new ToolReply(result.content(), result.tokenId());
        } catch (ToolCallException e) {
            throw failed(e);
        }
    }

    private static DataToolException failed(ToolCallException e) {
        var code = e.toolCode() == null ? e.reason().name() : e.reason().name() + ":" + e.toolCode();
        var message = e.reason() == ToolCallException.Reason.TOOL_ERROR ? "数据暂时查不到：" + e.getMessage()
                : e.getMessage();
        return new DataToolException(code, message);
    }
}
