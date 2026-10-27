package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回答记录与反馈。每次问答（包括拒答）都记下来并给回答一个 id；提问人可以对自己的回答点赞点踩、留言。
 */
@Service
public class AnswerLogService {

    private final AnswerRecordRepository records;

    AnswerLogService(AnswerRecordRepository records) {
        this.records = records;
    }

    /** 记下一次问答，返回带 id 的回答。 */
    @Transactional
    public Answer record(TenantId tenantId, UserId asker, String question, Answer answer) {
        return answer.withId(records.record(tenantId, asker, question.strip(), answer));
    }

    /**
     * @throws NoSuchElementException 回答不存在，或不是这位用户问的
     * @throws IllegalArgumentException 评分不是 UP / DOWN，或留言太长
     */
    @Transactional
    public void rate(TenantId tenantId, UserId by, long answerId, String rating, String comment) {
        AnswerRecord.Rating parsed;
        try {
            parsed = AnswerRecord.Rating.valueOf(rating == null ? "" : rating);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("评分只能是 UP 或 DOWN");
        }
        var record = find(tenantId, answerId);
        try {
            record.rate(by, parsed, comment);
        } catch (AnswerRecord.NotAskerException e) {
            throw notFound();
        }
        records.saveFeedback(record);
    }

    /** 撤销评价。 */
    @Transactional
    public void clearRating(TenantId tenantId, UserId by, long answerId) {
        var record = find(tenantId, answerId);
        try {
            record.clearRating(by);
        } catch (AnswerRecord.NotAskerException e) {
            throw notFound();
        }
        records.saveFeedback(record);
    }

    private AnswerRecord find(TenantId tenantId, long answerId) {
        return records.find(tenantId, answerId).orElseThrow(AnswerLogService::notFound);
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException("回答不存在");
    }
}
