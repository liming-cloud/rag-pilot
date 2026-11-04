package com.liming.rag.answering.application.conversation;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** 准备好的一轮：已校验、已分配 id、已登记停止信号。 */
public record PendingTurn(TenantId tenantId, UserId userId, long conversationId, long turnId, String question,
        @Nullable Long replacesTurnId, ConversationService.TurnContext context, CompletableFuture<Void> stop) {
}
