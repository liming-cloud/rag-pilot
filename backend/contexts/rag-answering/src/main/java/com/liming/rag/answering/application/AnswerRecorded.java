package com.liming.rag.answering.application;

import com.liming.rag.shared.TenantId;

/** 记下了一次问答（#145 C6）。事务提交后在后台算问题向量，知识缺口归类时直接用。 */
public record AnswerRecorded(TenantId tenantId, long answerId, String question) {
}
