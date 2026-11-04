package com.liming.rag.knowledge.application;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;

/**
 * 按谁的授权来过滤：账号本人和它所属的角色。能访问的知识库 = 直接授权给账号的 ∪ 授权给这些角色的（D-038）。
 * 角色由检索服务自己向 identity 查，不由调用方传入。
 */
public record Grantee(TenantId tenantId, UserId userId, List<Long> roleIds) {

    public Grantee {
        roleIds = List.copyOf(roleIds);
    }
}
