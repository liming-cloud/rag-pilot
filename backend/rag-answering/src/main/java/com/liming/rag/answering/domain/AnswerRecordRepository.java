package com.liming.rag.answering.domain;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.util.Optional;

/** 回答记录仓储。所有查询都带租户。 */
public interface AnswerRecordRepository {

    /** 记下一次问答连同引用，返回记录标识。 */
    long record(TenantId tenantId, UserId asker, String question, Answer answer);

    Optional<AnswerRecord> find(TenantId tenantId, long id);

    /** 保存评价和留言。 */
    void saveFeedback(AnswerRecord record);

    /** 保存处理状态、处理说明和处理人。 */
    void saveReview(AnswerRecord record);
}
