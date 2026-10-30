package com.liming.rag.answering.domain;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 会话仓储。所有查询都带租户；是不是本人由聚合判断。 */
public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> find(TenantId tenantId, long id);

    /** 员工自己的会话，按最后一次提问从新到旧。 */
    List<Conversation> listOwned(TenantId tenantId, UserId owner);

    /** 删除会话和它的轮次；回答记录不受影响。 */
    void delete(TenantId tenantId, long id);

    /** 删除最后一次提问早于 {@code before} 的会话，返回删除的个数。 */
    int deleteInactiveSince(Instant before);

    /** 预先分配轮次 id：生成开始时就要告诉前端，停止生成要用它。 */
    long nextTurnId();

    /**
     * 存一轮问答，接在主线最后一轮之后。{@code replacesTurnId} 不为 null 时，被替换的那一轮移出主线，
     * 新的一轮接在它的上一轮之后。
     */
    void addTurn(long conversationId, ConversationTurn turn, Long replacesTurnId);

    /** 主线上的轮次，按先后排列。 */
    List<ConversationTurn> mainLine(long conversationId);
}
