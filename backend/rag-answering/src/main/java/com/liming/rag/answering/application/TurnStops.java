package com.liming.rag.answering.application;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 正在生成的轮次和它们的停止信号。只有员工点“停止生成”才中止；断开连接、刷新页面不中止（D-036）。
 * 单实例部署，登记在内存里（D-005）。
 */
@Component
class TurnStops {

    private record Entry(long conversationId, CompletableFuture<Void> signal) {
    }

    private final Map<Long, Entry> running = new ConcurrentHashMap<>();

    CompletableFuture<Void> register(long conversationId, long turnId) {
        var signal = new CompletableFuture<Void>();
        running.put(turnId, new Entry(conversationId, signal));
        return signal;
    }

    /** 停止这个会话里的这一轮；已经结束或不属于这个会话时什么也不做。 */
    void stop(long conversationId, long turnId) {
        var entry = running.get(turnId);
        if (entry != null && entry.conversationId() == conversationId) {
            entry.signal().complete(null);
        }
    }

    void finished(long turnId) {
        running.remove(turnId);
    }
}
