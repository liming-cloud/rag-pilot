package com.liming.rag.answering.application.conversation;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 每个账号的提问频率（#145 B5，OWASP LLM10“无限制消耗”）：最近 1 分钟、最近 24 小时各不超过上限，超过时拒绝（429），
 * 不调用模型。单实例部署，计数在内存里，应用重启后清零。
 * 管理员的“重新回答”不经这里。
 */
@Component
class AskRateLimiter {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final AnsweringProperties.Limits limits;
    private final Clock clock;
    /** 每个账号最近 24 小时提问的时刻，从早到晚。 */
    private final Map<List<Long>, Deque<Instant>> asks = new HashMap<>();

    @Autowired
    AskRateLimiter(AnsweringProperties properties, Clock clock) {
        this(properties.limits(), clock);
    }

    AskRateLimiter(AnsweringProperties.Limits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * 记下这一次提问；超过上限时不记、抛出。
     *
     * @throws AnsweringException RATE_LIMITED
     */
    synchronized void acquire(TenantId tenantId, UserId userId) {
        var now = clock.instant();
        var times = asks.computeIfAbsent(List.of(tenantId.value(), userId.value()), key -> new ArrayDeque<>());
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(DAY))) {
            times.pollFirst();
        }
        long lastMinute = times.stream().filter(time -> time.isAfter(now.minus(MINUTE))).count();
        if (lastMinute >= limits.perMinute()) {
            throw new AnsweringException(AnsweringException.Reason.RATE_LIMITED, "提问太频繁，请过一分钟再问");
        }
        if (times.size() >= limits.perDay()) {
            throw new AnsweringException(AnsweringException.Reason.RATE_LIMITED,
                    "今天的提问已达上限（" + limits.perDay() + " 个），请明天再问；确有需要请联系管理员");
        }
        times.addLast(now);
    }
}
