package com.liming.rag.knowledge.application.knowledgebase;

import org.jspecify.annotations.Nullable;

/** 直接授权的账号：用户名和显示名一起给出。账号已被删除时只有 id。 */
public record KnowledgeBaseMember(long userId, @Nullable String username, @Nullable String displayName) {
}
