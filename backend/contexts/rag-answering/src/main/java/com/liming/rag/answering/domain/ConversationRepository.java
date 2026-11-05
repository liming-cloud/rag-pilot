package com.liming.rag.answering.domain;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** 会话仓储。所有查询都带租户；是不是本人由聚合判断。 */
public interface ConversationRepository {

    /**
     * 新会话插入并回填标识；已有的会话按加载时的版本号更新，返回带新版本号的聚合。
     *
     * @throws Conversation.ConcurrentChangeException 会话在加载之后被别的操作改过
     */
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
     * 存一轮问答。接在哪一轮之后由聚合算好（{@link Conversation#appendTurn}，即 {@code turn.parentTurnId()}），
     * 调用方先用 {@link #save} 保存会话；{@code replacesTurnId} 不为 null 时，被替换的那一轮移出主线，留着供审计。
     */
    void addTurn(long conversationId, ConversationTurn turn, @Nullable Long replacesTurnId);

    /** 主线上的轮次：从会话记下的最后一轮沿“上一轮”往回找，按先后排列。 */
    List<ConversationTurn> mainLine(long conversationId);
}
