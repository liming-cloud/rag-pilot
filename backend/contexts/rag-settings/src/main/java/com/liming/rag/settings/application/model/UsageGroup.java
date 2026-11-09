package com.liming.rag.settings.application.model;

import java.math.BigDecimal;

/**
 * 统计表的一行。{@code cost} 是按当前单价估算的费用（元），没填单价的模型不计入；{@code unpriced} 为 true 表示这一行里有这样的用量，
 * 费用偏低。
 */
public record UsageGroup(String key, long calls, long inputTokens, long outputTokens, BigDecimal cost,
        boolean unpriced) {
}
