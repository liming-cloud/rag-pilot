package com.liming.rag.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置。三种能力默认共用同一个 OpenAI 兼容地址和密钥（MODEL_API_KEY），各自可以单独覆盖。
 *
 * @param baseUrl OpenAI 兼容接口地址，含 /v1
 * @param apiKey 密钥，只从环境变量注入，不写进配置文件和日志
 * @param embedding 嵌入模型配置
 * @param timeout 单次调用超时
 */
@ConfigurationProperties("rag.ai")
public record AiProperties(String baseUrl, String apiKey, Embedding embedding, Duration timeout) {

    /**
     * @param provider openai：调用真实模型；hashing：本地确定性向量，只用于测试和离线开发
     * @param model 模型名
     * @param batchSize 每次请求的文本条数
     */
    public record Embedding(String provider, String model, int batchSize) {
    }
}
