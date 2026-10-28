package com.liming.rag.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/**
 * 通过 Spring AI 调用 OpenAI 兼容的嵌入接口（默认硅基流动 BAAI/bge-m3）。
 *
 * <p>入库在后台进行，不阻塞请求，所以遇到限流（429）和服务端错误时按退避重试 {@value #MAX_RETRIES} 次；
 * 仍失败时文档标记为失败并给出原因，由管理员重新上传。
 */
final class SpringAiEmbeddingClient implements EmbeddingClient {

    static final int MAX_RETRIES = 3;

    private final EmbeddingModel model;
    private final int batchSize;

    SpringAiEmbeddingClient(AiProperties properties) {
        var options = OpenAiEmbeddingOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.requireApiKey())
                .model(properties.embedding().model())
                .timeout(properties.timeout())
                .maxRetries(MAX_RETRIES)
                .build();
        this.model = OpenAiEmbeddingModel.builder().options(options).build();
        this.batchSize = Math.max(1, properties.embedding().batchSize());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        var vectors = new ArrayList<float[]>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            var batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
            List<float[]> result;
            try {
                result = model.embed(batch);
            } catch (RuntimeException e) {
                throw new ModelCallException("向量化失败：嵌入模型服务暂时不可用，请稍后重试", e);
            }
            if (result.size() != batch.size()) {
                throw new ModelCallException("向量化失败：模型返回的向量数量不对");
            }
            for (var vector : result) {
                if (vector.length != DIMENSIONS) {
                    throw new ModelCallException("向量化失败：模型返回 " + vector.length + " 维向量，系统要求 " + DIMENSIONS + " 维");
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
