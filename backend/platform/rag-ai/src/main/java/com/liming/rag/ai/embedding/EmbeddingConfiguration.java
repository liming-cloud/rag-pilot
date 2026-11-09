package com.liming.rag.ai.embedding;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.Providers;
import com.liming.rag.cache.Caches;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EmbeddingConfiguration {

    @Bean
    EmbeddingClient embeddingClient(AiProperties properties, ObjectProvider<ObservationRegistry> observations,
            Caches caches) {
        var provider = properties.embedding().provider();
        EmbeddingClient client = switch (provider) {
            case "openai" -> new SpringAiEmbeddingClient(properties,
                    observations.getIfAvailable(() -> ObservationRegistry.NOOP));
            case "hashing" -> new HashingEmbeddingClient();
            default -> throw Providers.unknown("embedding", provider, "openai 或 hashing");
        };
        var model = "openai".equals(provider) ? properties.embedding().model() : provider;
        return new CachedQuestionEmbeddings(client, model, caches);
    }
}
