package com.liming.rag.knowledge.api;

import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.util.List;

/**
 * 检索请求。
 *
 * <p>检索只在 {@code userId} 是成员的知识库里进行，这个过滤由检索服务负责，调用方不用也不能绕过。
 *
 * @param tenantId 租户
 * @param userId 提问的用户
 * @param knowledgeBaseIds 想缩小到的知识库；为空表示用户有权限的全部知识库，无权限的库会被忽略
 * @param text 用户问题
 * @param limit 返回条数上限
 */
public record SearchQuery(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String text, int limit) {

    public SearchQuery {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
    }
}
