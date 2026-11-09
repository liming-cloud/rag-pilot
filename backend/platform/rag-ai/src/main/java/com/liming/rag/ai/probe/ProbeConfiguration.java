package com.liming.rag.ai.probe;

import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelRoutes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProbeConfiguration {

    @Bean
    ModelProbe modelProbe(AiProperties properties, ModelRoutes routes) {
        return new OpenAiCompatibleProbe(properties, routes);
    }
}
