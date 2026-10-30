package com.liming.rag.answering.application;

import com.liming.rag.shared.TenantId;

/** 回答记录的查询端口，按时间从新到旧。 */
public interface AnswerReviews {

    /** 按筛选条件分页；返回的记录不带提问人名字（askerName 为 null），由调用方补上。 */
    Paged<AnswerReview> page(TenantId tenantId, AnswerReview.Query query);
}
