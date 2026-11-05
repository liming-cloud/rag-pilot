package com.liming.rag.settings.application.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** 模型管理要用到的外部能力端口（D-054）：环境变量里的默认配置、连通测试、读服务商的模型列表。都不返回密钥明文。 */
public interface ModelConnector {

    /** 这个用途在环境变量里配的地址、密钥和模型。 */
    ModelEndpoint environment(ModelPurpose purpose);

    /** 嵌入模型（只显示）：地址和模型名。 */
    ModelEndpoint embedding();

    /** 嵌入向量的维度，写死在表结构里。 */
    int embeddingDimensions();

    /** 对这个用途发一句极短的请求（重排送一对短文本），10 秒超时；不抛异常，失败写在结果里。 */
    ProbeResult probe(ModelPurpose purpose, ModelEndpoint endpoint);

    /**
     * 服务商提供的模型（OpenAI 兼容接口的 {@code GET /models}），按名称排序。
     *
     * @throws ModelConnectionException 连不上，或服务商不提供列表
     */
    List<String> models(String baseUrl, String apiKey);

    /**
     * @param latencyMs 请求耗时；没连上时为 null
     * @param message 给管理员看的中文说明，不含密钥
     */
    record ProbeResult(boolean ok, @Nullable Long latencyMs, String message) {
    }
}
