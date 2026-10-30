package com.liming.rag.answering.domain;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** 回答记录仓储。所有查询都带租户。 */
public interface AnswerRecordRepository {

    /** 记下一次问答连同引用，返回记录标识。 */
    long record(TenantId tenantId, UserId asker, String question, Answer answer);

    Optional<AnswerRecord> find(TenantId tenantId, long id);

    /**
     * 按 id 取回当时的回答（文字、是否拒答、引用位置），供会话展示历史。引用的原文不在回答记录里，
     * {@code excerpt} 为 null，由调用方按当前授权补上（D-036）。
     */
    Map<Long, Answer> answers(TenantId tenantId, Collection<Long> ids);

    /** 保存评价和留言。 */
    void saveFeedback(AnswerRecord record);

    /** 保存处理状态、处理说明和处理人。 */
    void saveReview(AnswerRecord record);
}
