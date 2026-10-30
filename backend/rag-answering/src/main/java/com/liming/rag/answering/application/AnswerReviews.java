package com.liming.rag.answering.application;

import com.liming.rag.shared.TenantId;
import java.util.List;

/** 回答记录的查询端口，按时间从新到旧。 */
public interface AnswerReviews {

    /** {@code includeResolved} 为 false 时只返回待处理的记录。 */
    List<AnswerReview> list(TenantId tenantId, AnswerReview.Filter filter, boolean includeResolved, int limit);
}
