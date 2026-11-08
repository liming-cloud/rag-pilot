package com.liming.rag.data.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Set;

/**
 * 工具调用的授权判定（D-056）：用户能不能调这个工具、能看哪些站点。系统判定，不交给模型；管理员同样不绕过。
 * 调用前先判定，拒绝就不发请求；返回后每一行的站点都要在 {@link ScopeDecision#units()} 里。
 */
public interface DataAccess {

    /**
     * @param tool 工具名
     * @param units 参数里指定的站点，没指定为空
     * @param regions 参数里指定的区域，没指定为空；两者都空表示不限定，取用户被授权的全部站点
     */
    ScopeDecision decide(TenantId tenantId, UserId userId, String tool, Set<String> units, Set<String> regions);
}
