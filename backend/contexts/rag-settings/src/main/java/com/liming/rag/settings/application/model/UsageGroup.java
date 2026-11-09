package com.liming.rag.settings.application.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * 统计表的一行。{@code cost} 是按当前单价估算的费用（元），没填单价的模型不计入；{@code unpriced} 为 true 表示这一行里有这样的用量，
 * 费用偏低。明细（{@code DETAIL}）行的 {@code key} 是日期，并带上用途、服务商域名和模型；合并后的行这三项为 null。
 */
public record UsageGroup(String key, @Nullable String purpose, @Nullable String provider, @Nullable String model,
        long calls, long inputTokens, long outputTokens, BigDecimal cost, boolean unpriced) {
}
