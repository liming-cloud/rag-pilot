package com.liming.rag.answering.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.shared.ErrorKind;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AskRateLimiterTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId STAFF = new UserId(7);
    private static final UserId OTHER = new UserId(8);

    private Instant now = Instant.parse("2026-11-04T08:00:00Z");

    private final AskRateLimiter limiter = new AskRateLimiter(new AnsweringProperties.Limits(3, 5), new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    @DisplayName("一分钟内超过上限时拒绝（429），过了一分钟又能问；每个账号分开算")
    void limitsPerMinute() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(TENANT, STAFF);
        }
        assertThatThrownBy(() -> limiter.acquire(TENANT, STAFF)).isInstanceOfSatisfying(AnsweringException.class, e -> {
            assertThat(e.kind()).isEqualTo(ErrorKind.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("ANSWERING_RATE_LIMITED");
            assertThat(e.getMessage()).contains("太频繁");
        });
        limiter.acquire(TENANT, OTHER);

        now = now.plus(Duration.ofSeconds(61));
        limiter.acquire(TENANT, STAFF);
    }

    @Test
    @DisplayName("24 小时内超过上限时拒绝并说明是当天的上限，过了 24 小时恢复")
    void limitsPerDay() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire(TENANT, STAFF);
            now = now.plus(Duration.ofMinutes(5));
        }
        assertThatThrownBy(() -> limiter.acquire(TENANT, STAFF)).isInstanceOfSatisfying(AnsweringException.class,
                e -> assertThat(e.getMessage()).contains("5"));

        now = now.plus(Duration.ofHours(24));
        limiter.acquire(TENANT, STAFF);
    }

    @Test
    @DisplayName("上限必须是正数，非法配置启动失败")
    void rejectsInvalidLimits() {
        assertThatThrownBy(() -> new AnsweringProperties.Limits(0, 300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnsweringProperties.Limits(10, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
