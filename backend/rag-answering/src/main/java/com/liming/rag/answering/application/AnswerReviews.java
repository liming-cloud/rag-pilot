package com.liming.rag.answering.application;

import com.liming.rag.common.TenantId;
import java.util.List;

/** 回答记录的查询端口，按时间从新到旧。 */
public interface AnswerReviews {

    List<AnswerReview> list(TenantId tenantId, AnswerReview.Filter filter, int limit);
}
