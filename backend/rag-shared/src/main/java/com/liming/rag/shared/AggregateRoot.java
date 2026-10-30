package com.liming.rag.shared;

/**
 * 标记聚合根：一致性边界的根，外部只能通过它修改聚合内的状态，仓储只为聚合根而建。只标识角色，不提供任何行为。
 *
 * <p>聚合只在 knowledge、answering 的 domain 包（AGENTS.md），由架构规则检查。
 */
public interface AggregateRoot {
}
