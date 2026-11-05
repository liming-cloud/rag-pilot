package com.liming.rag.ai;

import com.liming.rag.cache.Caches;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class AiConfiguration {

    @Bean
    EmbeddingClient embeddingClient(AiProperties properties, ObjectProvider<ObservationRegistry> observations,
            Caches caches) {
        var provider = properties.embedding().provider();
        EmbeddingClient client = switch (provider) {
            case "openai" -> new SpringAiEmbeddingClient(properties,
                    observations.getIfAvailable(() -> ObservationRegistry.NOOP));
            case "hashing" -> new HashingEmbeddingClient();
            default -> throw unknown("embedding", provider, "openai 或 hashing");
        };
        var model = "openai".equals(provider) ? properties.embedding().model() : provider;
        return new CachedQuestionEmbeddings(client, model, caches);
    }

    @Bean
    ModelRoutes modelRoutes(AiProperties properties, ObjectProvider<ModelRouting> routing) {
        return new ModelRoutes(properties, routing);
    }

    @Bean
    ModelProbe modelProbe(AiProperties properties, ModelRoutes routes) {
        return new OpenAiCompatibleProbe(properties, routes);
    }

    @Bean
    RerankClient rerankClient(AiProperties properties, ModelRoutes routes) {
        return switch (properties.rerank().provider()) {
            case "openai" -> new HttpRerankClient(properties, routes);
            case "lexical" -> new LexicalRerankClient();
            default -> throw unknown("rerank", properties.rerank().provider(), "openai 或 lexical");
        };
    }

    @Bean
    TextGeneration textGeneration(AiProperties properties, ModelRoutes routes,
            ObjectProvider<OpenAiChatModels> chatModels) {
        return switch (properties.chat().provider()) {
            case "openai" -> new SpringAiTextGeneration(properties, chatModels.getObject(), routes);
            case "extractive" -> new ExtractiveTextGeneration();
            default -> throw unknown("chat", properties.chat().provider(), "openai 或 extractive");
        };
    }

    private static IllegalStateException unknown(String capability, String value, String allowed) {
        return new IllegalStateException("rag.ai." + capability + ".provider 只能是 " + allowed + "，当前为：" + value);
    }

    @Bean
    QueryRewriting queryRewriting(AiProperties properties, ModelRoutes routes,
            ObjectProvider<OpenAiChatModels> chatModels) {
        return switch (properties.chat().provider()) {
            case "openai" -> new SpringAiQueryRewriting(chatModels.getObject(), routes);
            // 本地确定性实现不改写：低分改写按“模型没给出有效改写”处理。
            case "extractive" -> (instructions, question) -> "";
            default -> throw unknown("chat", properties.chat().provider(), "openai 或 extractive");
        };
    }

    /** 文字生成和问题改写共用的对话模型；只在用真实模型时创建（本地确定性实现不需要密钥）。 */
    @Bean
    @Lazy
    OpenAiChatModels openAiChatModels(AiProperties properties, ObjectProvider<ObservationRegistry> observations) {
        return new OpenAiChatModels(properties, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
