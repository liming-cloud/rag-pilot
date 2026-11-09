package com.liming.rag.settings.application.model;

import java.time.LocalDate;

/** 某一天、某个用途、服务商和模型的用量合计。 */
public record UsageRow(LocalDate day, String purpose, String provider, String model, long calls, long inputTokens,
        long outputTokens) {
}
