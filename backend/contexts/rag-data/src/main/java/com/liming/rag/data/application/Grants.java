package com.liming.rag.data.application;

import java.util.Set;

/** 授权给一个用户或角色的内容：可调用的工具、可查看的站点、可查看的区域。 */
public record Grants(Set<String> tools, Set<String> units, Set<String> regions) {

    public Grants {
        tools = Set.copyOf(tools);
        units = Set.copyOf(units);
        regions = Set.copyOf(regions);
    }

    public static Grants none() {
        return new Grants(Set.of(), Set.of(), Set.of());
    }
}
