package com.liming.rag.ai.rewriting;

import com.liming.rag.ai.QueryRewriting;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelRoutes;
import com.liming.rag.ai.common.OpenAiChatModels;
import com.liming.rag.ai.common.Providers;
import com.liming.rag.ai.common.UsageReports;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class QueryRewritingConfiguration {

    @Bean
    QueryRewriting queryRewriting(AiProperties properties, ModelRoutes routes,
            ObjectProvider<OpenAiChatModels> chatModels, UsageReports usage) {
        return switch (properties.chat().provider()) {
            case "openai" -> new SpringAiQueryRewriting(chatModels.getObject(), routes, usage);
            // 本地确定性实现不改写：低分改写按“模型没给出有效改写”处理。
            case "extractive" -> (instructions, question) -> "";
            default -> throw Providers.unknown("chat", properties.chat().provider(), "openai 或 extractive");
        };
    }
}
