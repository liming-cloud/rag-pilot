package com.liming.rag.identity.api;

import com.liming.rag.shared.TenantId;

/** 角色被删除。授权给这个角色的知识库要一并收回。在删除角色的事务里同步发布。 */
public record RoleDeleted(TenantId tenantId, long roleId) {
}
