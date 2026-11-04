package com.liming.rag.settings.application.model;

import com.liming.rag.ai.ModelUsage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 一个服务商，给界面显示：密钥只给后 4 位；环境变量里的服务商 id 为 null。 */
public record ProviderView(@Nullable Long id, String name, String baseUrl, @Nullable String apiKeyLast4, boolean fromDatabase,
        List<ModelUsage> usedBy) {
}
