package com.liming.rag.ai.generation;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelRoutes;
import com.liming.rag.ai.common.OpenAiChatModels;
import com.liming.rag.ai.common.Providers;
import com.liming.rag.ai.common.UsageReports;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TextGenerationConfiguration {

    @Bean
    TextGeneration textGeneration(AiProperties properties, ModelRoutes routes,
            ObjectProvider<OpenAiChatModels> chatModels, UsageReports usage) {
        return switch (properties.chat().provider()) {
            case "openai" -> new SpringAiTextGeneration(properties, chatModels.getObject(), routes, usage);
            case "extractive" -> new ExtractiveTextGeneration();
            default -> throw Providers.unknown("chat", properties.chat().provider(), "openai 或 extractive");
        };
    }
}
