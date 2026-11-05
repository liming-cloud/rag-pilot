package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** knowledge_base_member 的一行：库直接授权给的账号（主键是三列，没有 id）。 */
@TableName("knowledge_base_member")
@NullUnmarked
final class KnowledgeBaseMemberDO {

    Long tenantId;
    Long knowledgeBaseId;
    Long userId;
    OffsetDateTime createdAt;

    Long getTenantId() {
        return tenantId;
    }

    Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    Long getUserId() {
        return userId;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
