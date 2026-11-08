package com.liming.rag.data.application.query;

import com.liming.rag.data.api.DataCatalog.DataTool;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 客户系统只读工具的端口（D-054），实现在 infrastructure/ai，经平台组件 rag-ai 调用。 */
public interface DataTools {

    /**
     * 客户系统提供的工具（{@code tools/list}，服务身份）。
     *
     * @throws DataToolException 取不到
     */
    List<DataTool> list();

    /**
     * 以提问人的身份调用一个工具，令牌的数据范围是 {@code units}。
     *
     * @param subject 提问人的不透明用户标识
     * @throws DataToolException 超时、被拒、限流、工具报错或协议错误
     */
    ToolReply call(String tool, Map<String, Object> arguments, String subject, Set<String> units);
}
