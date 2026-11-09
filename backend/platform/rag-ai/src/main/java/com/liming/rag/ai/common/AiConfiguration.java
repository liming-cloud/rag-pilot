package com.liming.rag.ai.common;

import com.liming.rag.ai.ModelRouting;
import com.liming.rag.ai.UsageRecording;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.embedding.observation.EmbeddingModelMeterObservationHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** 各能力共用的装配：模型路由、对话模型、Spring AI 的指标处理器。 */
@Configuration(proxyBeanMethods = false)
class AiConfiguration {

    @Bean
    ModelRoutes modelRoutes(AiProperties properties, ObjectProvider<ModelRouting> routing) {
        return new ModelRoutes(properties, routing);
    }

    @Bean
    UsageReports usageReports(ObjectProvider<UsageRecording> recording) {
        return new UsageReports(recording);
    }

    /** 文字生成和问题改写共用的对话模型；只在用真实模型时创建（本地确定性实现不需要密钥）。 */
    @Bean
    @Lazy
    OpenAiChatModels openAiChatModels(AiProperties properties, ObjectProvider<ObservationRegistry> observations) {
        return new OpenAiChatModels(properties, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * 把模型调用的 token 用量记成指标 {@code gen_ai.client.token.usage}（#145）。Spring AI 的 starter 本来会注册它们，
     * 这里没用 starter，要自己注册；调用耗时 {@code gen_ai.client.operation} 由 Spring Boot 的观测处理器记下。
     */
    @Bean
    ChatModelMeterObservationHandler chatModelMeterObservationHandler(MeterRegistry meters) {
        return new ChatModelMeterObservationHandler(meters);
    }

    @Bean
    EmbeddingModelMeterObservationHandler embeddingModelMeterObservationHandler(MeterRegistry meters) {
        return new EmbeddingModelMeterObservationHandler(meters);
    }
}
