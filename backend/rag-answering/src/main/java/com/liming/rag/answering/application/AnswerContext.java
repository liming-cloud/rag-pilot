package com.liming.rag.answering.application;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Map;
import org.springframework.ai.rag.Query;

/**
 * 问答流程里 {@link Query#context()} 的键。提问人的租户、身份和想缩小到的知识库都放在这里，
 * 由检索器交给 knowledge 模块做权限过滤。
 */
final class AnswerContext {

    static final String TENANT_ID = "tenantId";
    static final String USER_ID = "userId";
    static final String KNOWLEDGE_BASE_IDS = "knowledgeBaseIds";
    /** 会话里前几轮的对话（D-036），拼证据时放在问题前面；没有时不放。 */
    static final String HISTORY = "history";

    private AnswerContext() {
    }

    static Query query(String question, TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds) {
        return query(question, tenantId, userId, knowledgeBaseIds, "");
    }

    static Query query(String question, TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String history) {
        return Query.builder()
                .text(question)
                .context(Map.of(TENANT_ID, tenantId, USER_ID, userId,
                        KNOWLEDGE_BASE_IDS, knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds),
                        HISTORY, history == null ? "" : history))
                .build();
    }
}
