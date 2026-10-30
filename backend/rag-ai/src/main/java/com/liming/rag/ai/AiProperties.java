package com.liming.rag.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置。嵌入、重排、对话共用同一个 OpenAI 兼容地址和密钥（MODEL_API_KEY）。
 *
 * <p>每种能力都有 provider：openai 调用真实模型；其余取值是本地确定性实现，只用于测试和没有密钥时的离线开发，
 * 不具备真实模型的能力，不能用于评测。
 *
 * @param baseUrl OpenAI 兼容接口地址，含 /v1
 * @param apiKey 密钥，只从环境变量注入，不写进配置文件和日志
 * @param timeout 单次调用超时
 */
@ConfigurationProperties("rag.ai")
public record AiProperties(String baseUrl, String apiKey, Duration timeout, Embedding embedding, Rerank rerank,
        Chat chat) {

    /** @param provider openai 或 hashing */
    public record Embedding(String provider, String model, int batchSize) {
    }

    /** @param provider openai 或 lexical */
    public record Rerank(String provider, String model) {
    }

    /**
     * @param provider openai 或 extractive
     * @param hedgeAfter 流式生成过了这么久还没出首字，就再发一个备用请求，用先出字的那个；为空时不发
     * @param hedgeModel 备用请求用的模型，为空时与 {@code model} 相同
     * @param lightModel 轻量任务（如会话标题）用的模型，为空时与 {@code model} 相同（D-036）
     */
    public record Chat(String provider, String model, double temperature, int maxTokens, Duration hedgeAfter,
            String hedgeModel, String lightModel) {
    }

    String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置模型密钥：请设置环境变量 MODEL_API_KEY");
        }
        return apiKey;
    }
}
