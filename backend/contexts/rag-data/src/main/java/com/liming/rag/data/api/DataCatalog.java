package com.liming.rag.data.api;

import java.util.List;

/**
 * 提问人能用的工具（授权的、部署配置了的、客户系统也提供的）和能看的站点。
 *
 * @param units 能看的站点，站点主数据取不到时名称用编号
 */
public record DataCatalog(List<DataTool> tools, List<DataUnit> units) {

    public DataCatalog {
        tools = List.copyOf(tools);
        units = List.copyOf(units);
    }

    public static DataCatalog empty() {
        return new DataCatalog(List.of(), List.of());
    }

    /** 一个可用的工具；{@code inputSchema} 是客户系统给的参数 JSON Schema（JSON 文本）。 */
    public record DataTool(String name, String title, String description, String inputSchema) {
    }

    /** 一个能看的站点。 */
    public record DataUnit(String id, String name) {
    }
}
