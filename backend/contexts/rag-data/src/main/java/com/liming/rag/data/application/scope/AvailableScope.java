package com.liming.rag.data.application.scope;

import java.util.Set;

/** 提问人可用的工具和可查看的站点（区域已展开），见 {@link DataScopeService#available}。 */
public record AvailableScope(Set<String> tools, Set<String> units) {

    public AvailableScope {
        tools = Set.copyOf(tools);
        units = Set.copyOf(units);
    }
}
