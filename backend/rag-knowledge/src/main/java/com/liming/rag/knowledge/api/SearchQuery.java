package com.liming.rag.knowledge.api;

import com.liming.rag.common.TenantId;
import java.util.List;

/**
 * 检索请求。
 *
 * @param tenantId 租户
 * @param knowledgeBaseIds 调用方已确认当前用户有权访问的知识库；检索只在这些库里进行
 * @param text 用户问题
 * @param limit 返回条数上限
 */
public record SearchQuery(TenantId tenantId, List<Long> knowledgeBaseIds, String text, int limit) {

    public SearchQuery {
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    }
}
