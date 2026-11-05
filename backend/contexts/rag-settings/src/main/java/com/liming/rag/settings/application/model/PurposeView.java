package com.liming.rag.settings.application.model;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/** 一个用途当前的配置，给界面显示。 */
public record PurposeView(ModelPurpose purpose, @Nullable Long providerId, String providerName, String model,
        boolean fromDatabase, String updatedBy, OffsetDateTime updatedAt) {
}
