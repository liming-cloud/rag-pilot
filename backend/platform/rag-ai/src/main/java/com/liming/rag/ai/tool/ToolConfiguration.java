package com.liming.rag.ai.tool;

import com.liming.rag.ai.ToolCaller;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ToolConfiguration {

    @Bean
    ToolTokenIssuer toolTokenIssuer(ToolProperties properties, Clock clock) {
        return new ToolTokenIssuer(properties, clock);
    }

    @Bean
    ToolCaller toolCaller(ToolProperties properties, ToolTokenIssuer tokens,
            ObjectProvider<ObservationRegistry> observations) {
        return new McpToolCaller(properties, tokens, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
