package com.liming.rag.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    @DisplayName("存的是带随机盐的 BCrypt 摘要：同一个密码每次不同，都能比对上；错误密码和 null 比对不上")
    void hashesWithSalt() {
        var first = hasher.hash("staff-password");
        var second = hasher.hash("staff-password");

        assertThat(first).startsWith("$2a$").isNotEqualTo(second).doesNotContain("staff-password");
        assertThat(hasher.matches("staff-password", first)).isTrue();
        assertThat(hasher.matches("staff-password", second)).isTrue();
        assertThat(hasher.matches("wrong-password", first)).isFalse();
        assertThat(hasher.matches(null, first)).isFalse();
    }
}
