package com.liming.rag.answering.domain;

import com.liming.rag.shared.TenantId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** 回答记录仓储。所有查询都带租户；加载时带上问题、回答、引用位置和检索范围（#136）。 */
public interface AnswerRecordRepository {

    /** 记下一条新的回答记录，连同引用，返回带标识的聚合。问题、回答、引用写入后不再变。 */
    AnswerRecord add(AnswerRecord record);

    Optional<AnswerRecord> find(TenantId tenantId, long id);

    /**
     * 按 id 取一批，供会话展示历史；不存在的 id 不返回。引用的原文不在回答记录里，{@code excerpt} 为 null，
     * 由调用方按当前授权补上（D-036）。
     */
    Map<Long, AnswerRecord> findAll(TenantId tenantId, Collection<Long> ids);

    /** 保存评价和留言。 */
    void saveFeedback(AnswerRecord record);

    /** 保存处理状态、处理说明和处理人。 */
    void saveReview(AnswerRecord record);
}
