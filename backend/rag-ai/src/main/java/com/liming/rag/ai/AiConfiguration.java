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
            default -> throw unknown("embedding", properties.embedding().provider(), "openai 或 hashing");
        };
    }

    @Bean
    RerankClient rerankClient(AiProperties properties) {
        return switch (properties.rerank().provider()) {
            case "openai" -> new HttpRerankClient(properties);
            case "lexical" -> new LexicalRerankClient();
            default -> throw unknown("rerank", properties.rerank().provider(), "openai 或 lexical");
        };
    }

    @Bean
    ChatGateway chatGateway(AiProperties properties) {
        return switch (properties.chat().provider()) {
            case "openai" -> new SpringAiChatGateway(properties);
            case "extractive" -> new ExtractiveChatGateway();
            default -> throw unknown("chat", properties.chat().provider(), "openai 或 extractive");
        };
    }

    @Bean
    DocumentReaders documentReaders() {
        return new LocalDocumentReaders();
    }

    private static IllegalStateException unknown(String capability, String value, String allowed) {
        return new IllegalStateException("rag.ai." + capability + ".provider 只能是 " + allowed + "，当前为：" + value);
    }
}
