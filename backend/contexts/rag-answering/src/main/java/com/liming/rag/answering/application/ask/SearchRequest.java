package com.liming.rag.answering.application.ask;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Objects;

/**
 * 一次检索：检索用的文字和提问人的身份、想缩小到的知识库（#145：替代框架查询对象里的 context Map，带类型，缺身份时无法构造）。
 * 权限过滤不在这里，在 knowledge 的检索 SQL 里；这里只负责把提问人的身份原样交过去。
 */
record SearchRequest(String text, TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds) {

    SearchRequest {
        Objects.requireNonNull(tenantId, "检索缺少租户");
        Objects.requireNonNull(userId, "检索缺少提问人");
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
    }

    /** 同一个提问人、同样的范围，换一段检索文字（检索规划、行业词扩展、低分改写后）。 */
    SearchRequest withText(String newText) {
        return new SearchRequest(newText, tenantId, userId, knowledgeBaseIds);
    }
}
