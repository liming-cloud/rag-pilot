package com.liming.rag.ai.rerank;

import com.liming.rag.ai.RerankClient;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelRoutes;
import com.liming.rag.ai.common.Providers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RerankConfiguration {

    @Bean
    RerankClient rerankClient(AiProperties properties, ModelRoutes routes) {
        return switch (properties.rerank().provider()) {
            case "openai" -> new HttpRerankClient(properties, routes);
            case "lexical" -> new LexicalRerankClient();
            default -> throw Providers.unknown("rerank", properties.rerank().provider(), "openai 或 lexical");
        };
    }
}
