package com.liming.rag.ai;

import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * OpenAI 兼容的对话模型（#145）：按地址和密钥缓存，换模型只换调用选项，换服务商才新建客户端；都带上观测注册表，
 * 框架自带的观测由 Actuator 汇总成指标。文字生成和问题改写共用。
 */
final class OpenAiChatModels {

    /** 不需要密钥的服务（如内网自建的 vLLM）也要给 OpenAI 客户端一个值。 */
    private static final String NO_KEY = "EMPTY";

    private final AiProperties properties;
    private final ObservationRegistry observations;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    OpenAiChatModels(AiProperties properties, ObservationRegistry observations) {
        properties.requireApiKey();
        this.properties = properties;
        this.observations = observations;
    }

    ObservationRegistry observations() {
        return observations;
    }

    /** 每次调用都按当前配置取服务商和模型，管理端保存后下一次请求就生效（D-043）。不重试，失败直接告诉用户。 */
    OpenAiChatOptions options(ModelRoute route) {
        return OpenAiChatOptions.builder()
                .baseUrl(route.baseUrl())
                .apiKey(route.apiKey() == null ? NO_KEY : route.apiKey())
                .model(route.model())
                .temperature(properties.chat().temperature())
                .maxTokens(properties.chat().maxTokens())
                .timeout(properties.timeout())
                .maxRetries(0)
                .build();
    }

    ChatModel model(ModelRoute route) {
        return models.computeIfAbsent(route.baseUrl() + "\n" + route.apiKey(),
                key -> OpenAiChatModel.builder().options(options(route)).observationRegistry(observations).build());
    }
}
