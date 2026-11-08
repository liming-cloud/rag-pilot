package com.liming.rag.data.application;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 数据问答授权配置（{@code rag.data}，D-059）。取值在构造时校验，非法时应用启动失败。
 *
 * @param endpoint    站点主数据所在的客户 MCP 连接名，对应 {@code rag.ai.tools.endpoints.<名>}
 * @param unitsTool  返回站点及所属区域的只读工具名
 * @param tools       可授权的工具清单（管理页从这里选）；工具能不能调只由授权决定，客户端工具注解不作依据（D-058）
 */
@ConfigurationProperties("rag.data")
public record DataProperties(@DefaultValue("retail") String endpoint, @DefaultValue("list_stores") String unitsTool,
        @DefaultValue List<Tool> tools) {

    /** @param name 工具名；@param title 管理页显示的中文名 */
    public record Tool(String name, String title) {

        public Tool {
            if (name == null || name.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("rag.data.tools 的 name、title 都不能为空");
            }
        }
    }

    public DataProperties {
        if (endpoint == null || endpoint.isBlank() || unitsTool == null || unitsTool.isBlank()) {
            throw new IllegalArgumentException("rag.data.endpoint、rag.data.units-tool 不能为空");
        }
        tools = List.copyOf(tools);
        var names = new HashSet<String>();
        for (var tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("rag.data.tools 里工具名重复：" + tool.name());
            }
        }
    }
}
