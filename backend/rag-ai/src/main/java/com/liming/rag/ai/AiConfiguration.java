package com.liming.rag.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    EmbeddingClient embeddingClient(AiProperties properties) {
        return switch (properties.embedding().provider()) {
            case "openai" -> new SpringAiEmbeddingClient(properties);
            case "hashing" -> new HashingEmbeddingClient();
            default -> throw new IllegalStateException(
                    "rag.ai.embedding.provider 只能是 openai 或 hashing，当前为：" + properties.embedding().provider());
        };
    }
}
