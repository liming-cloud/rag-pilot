package com.liming.rag.settings.application;

import com.liming.rag.ai.ModelUsage;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 一个用途在界面保存过的配置；没有保存过的用途用环境变量。
 *
 * @param providerId null 表示用环境变量里的服务商，只换模型名
 */
public record PurposeSetting(ModelUsage purpose, @Nullable Long providerId, String model, String updatedByName,
        OffsetDateTime updatedAt) {
}
