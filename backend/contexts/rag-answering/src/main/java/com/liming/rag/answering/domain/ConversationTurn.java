package com.liming.rag.answering.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 会话里的一轮问答。按树存：{@code parentTurnId} 是它接着的上一轮；被“重新生成”“修改问题”替换后，
 * 旧的一轮仍保留（不在主线上），供管理端审计。
 *
 * @param answerRecordId 回答记录；停止时没留下可核对的内容、或出错时为 null
 * @param knowledgeBaseIds 这一轮实际检索的知识库（会话所选的库与当时的授权取交集）
 */
public record ConversationTurn(long id, Long parentTurnId, String question, Status status, Long answerRecordId,
        List<Long> knowledgeBaseIds, Instant askedAt) {

    public enum Status {
        ANSWERED,
        /** 员工点了停止生成。 */
        STOPPED,
        /** 模型服务出错，这一轮没有结果。 */
        FAILED
    }

    public ConversationTurn {
        Objects.requireNonNull(question);
        Objects.requireNonNull(status);
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        Objects.requireNonNull(askedAt);
    }
}
