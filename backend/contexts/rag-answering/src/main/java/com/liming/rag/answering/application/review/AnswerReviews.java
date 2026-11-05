package com.liming.rag.answering.application.review;

import com.liming.rag.shared.Paged;
import com.liming.rag.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.List;

/** 回答记录的查询端口，按时间从新到旧。 */
public interface AnswerReviews {

    /** 按筛选条件分页；返回的记录不带提问人名字（askerName 为 null），由调用方补上。 */
    Paged<AnswerReview> page(TenantId tenantId, AnswerReview.Query query);

    /** {@code from}（含）到 {@code to}（不含）之间的全部记录，按时间先后，最多 {@code limit} 条；不带提问人名字。 */
    List<AnswerReview> between(TenantId tenantId, OffsetDateTime from, OffsetDateTime to, int limit);
}
