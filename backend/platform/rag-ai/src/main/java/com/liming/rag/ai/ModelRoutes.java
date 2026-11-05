package com.liming.rag.ai;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 决定每次调用用哪个服务商和模型：界面配置过的用途以系统设置为准（{@link ModelRouting}），否则用环境变量（D-043）。
 * 系统设置模块没有加载时（如只启动 rag-ai 的测试）全部用环境变量。
 */
final class ModelRoutes {

    private final AiProperties properties;
    private final ObjectProvider<ModelRouting> routing;

    ModelRoutes(AiProperties properties, ObjectProvider<ModelRouting> routing) {
        this.properties = properties;
        this.routing = routing;
    }

    ModelRoute resolve(ModelUsage usage) {
        var configured = routing.getIfAvailable();
        if (configured != null) {
            var override = configured.override(usage);
            if (override.isPresent()) {
                return override.get();
            }
        }
        return environment(usage);
    }

    ModelRoute environment(ModelUsage usage) {
        var chat = properties.chat();
        var model = switch (usage) {
            case ANSWER -> chat.model();
            case HEDGE -> orElse(chat.hedgeModel(), chat.model());
            case LIGHT -> orElse(chat.lightModel(), chat.model());
            case RERANK -> properties.rerank().model();
        };
        return new ModelRoute(properties.baseUrl(), properties.apiKey(), model);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
