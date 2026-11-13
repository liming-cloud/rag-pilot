package com.liming.rag.knowledge.application;

/** 知识库列表的一行：文档数、失败数、即将到期数、已失效数、授权给几个角色、几个账号，以及体检需整理的文档数（#218）。 */
public record KnowledgeBaseSummary(long id, String name, long documentCount, long failedCount, long expiringCount,
        long expiredCount, long roleGrantCount, long memberCount, long needsWorkCount) {
}
