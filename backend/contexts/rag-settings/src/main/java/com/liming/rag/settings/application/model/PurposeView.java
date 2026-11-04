package com.liming.rag.settings.application.model;

import com.liming.rag.ai.ModelUsage;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/** 一个用途当前的配置，给界面显示。 */
public record PurposeView(ModelUsage purpose, @Nullable Long providerId, String providerName, String model,
        boolean fromDatabase, String updatedBy, OffsetDateTime updatedAt) {
}
