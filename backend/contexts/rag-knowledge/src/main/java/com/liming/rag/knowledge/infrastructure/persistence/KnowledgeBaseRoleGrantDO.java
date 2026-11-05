package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** knowledge_base_role_grant 的一行：库授权给的角色（主键是三列，没有 id）。 */
@TableName("knowledge_base_role_grant")
@NullUnmarked
final class KnowledgeBaseRoleGrantDO {

    Long tenantId;
    Long knowledgeBaseId;
    Long roleId;
    OffsetDateTime createdAt;

    Long getTenantId() {
        return tenantId;
    }

    Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    Long getRoleId() {
        return roleId;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
