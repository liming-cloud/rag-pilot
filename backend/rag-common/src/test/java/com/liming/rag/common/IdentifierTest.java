package com.liming.rag.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentifierTest {

    @Test
    @DisplayName("正数标识可以创建，并按值相等")
    void positiveIdentifiersAreValueObjects() {
        assertThat(new TenantId(1)).isEqualTo(new TenantId(1));
        assertThat(new UserId(42).value()).isEqualTo(42);
    }

    @Test
    @DisplayName("零或负数标识被拒绝")
    void nonPositiveIdentifiersAreRejected() {
        assertThatThrownBy(() -> new TenantId(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
