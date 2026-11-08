package com.liming.rag.data.api;

import java.util.Set;

/**
 * 一次工具调用的数据范围判定结果。放行时 {@code units} 是这次调用允许的站点（区域已按站点主数据展开），
 * 写进令牌的 {@code data_scope}，也是返回后逐行校验的依据；拒绝时 {@code units} 为空，调用方不得发出请求。
 *
 * @param reason 拒绝原因，给用户看的中文，不含内部信息；放行时为空串
 */
public record ScopeDecision(boolean allowed, Set<String> units, String reason) {

    public ScopeDecision {
        units = Set.copyOf(units);
    }

    public static ScopeDecision allow(Set<String> units) {
        return new ScopeDecision(true, units, "");
    }

    public static ScopeDecision deny(String reason) {
        return new ScopeDecision(false, Set.of(), reason);
    }
}
