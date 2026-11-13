package com.liming.rag.ai.embedding;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelCallFailures;
import com.liming.rag.ai.common.ModelCallUsage;
import com.liming.rag.ai.common.UsageReports;
import com.openai.errors.RateLimitException;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/**
 * 通过 Spring AI 调用 OpenAI 兼容的嵌入接口（默认硅基流动 BAAI/bge-m3），带上观测注册表，框架自带的观测由 Actuator 汇总成指标（#145）。
 *
 * <p>遇到限流（429）和服务端错误时由 OpenAI SDK 按退避重试 {@value #MAX_RETRIES} 次。入库在后台进行，不阻塞请求：
 * 大文档的某一批在 SDK 重试后仍被限流时，再按 {@link #RATE_LIMIT_WAITS} 等一等，只重做这一批，已算好的批次保留（#226）；
 * 等完仍被限流时文档按“模型暂时不可用”失败，稍后自动重试。只有 {@link #embedForIngestion} 这样等，在线请求不等。
 */
final class SpringAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingClient.class);

    static final int MAX_RETRIES = 3;

    /** 批量向量化被限流后每轮的等待时间，合计约 3 分钟，覆盖服务商按分钟计的限流窗口。 */
    static final List<Duration> RATE_LIMIT_WAITS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(20), Duration.ofSeconds(40), Duration.ofSeconds(60), Duration.ofSeconds(60));

    private final EmbeddingModel model;
    private final int batchSize;
    private final String modelName;
    private final String baseUrl;
    private final UsageReports usage;
    private final List<Duration> rateLimitWaits;

    SpringAiEmbeddingClient(AiProperties properties, ObservationRegistry observations, UsageReports usage) {
        this(properties, observations, usage, RATE_LIMIT_WAITS);
    }

    SpringAiEmbeddingClient(AiProperties properties, ObservationRegistry observations, UsageReports usage,
            List<Duration> rateLimitWaits) {
        var options = OpenAiEmbeddingOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.requireApiKey())
                .model(properties.embedding().model())
                .timeout(properties.timeout())
                .maxRetries(MAX_RETRIES)
                .build();
        this.model = OpenAiEmbeddingModel.builder().options(options).observationRegistry(observations).build();
        this.batchSize = Math.max(1, properties.embedding().batchSize());
        this.modelName = properties.embedding().model();
        this.baseUrl = properties.baseUrl();
        this.usage = usage;
        this.rateLimitWaits = rateLimitWaits;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embed(texts, List.of());
    }

    @Override
    public List<float[]> embedForIngestion(List<String> texts) {
        return embed(texts, rateLimitWaits);
    }

    private List<float[]> embed(List<String> texts, List<Duration> waits) {
        var vectors = new ArrayList<float[]>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            vectors.addAll(embedBatch(texts.subList(start, Math.min(texts.size(), start + batchSize)), waits));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch, List<Duration> waits) {
        List<float[]> result;
        try {
            result = call(batch, waits);
        } catch (RuntimeException e) {
            throw ModelCallFailures.failed("embedding", modelName, "向量化失败：嵌入模型服务暂时不可用，请稍后重试", e);
        }
        if (result.size() != batch.size()) {
            throw new ModelCallException("向量化失败：模型返回的向量数量不对");
        }
        for (var vector : result) {
            if (vector.length != DIMENSIONS) {
                throw new ModelCallException("向量化失败：模型返回 " + vector.length + " 维向量，系统要求 " + DIMENSIONS + " 维");
            }
        }
        return result;
    }

    private List<float[]> call(List<String> batch, List<Duration> waits) {
        for (int round = 0; ; round++) {
            try {
                var response = model.embedForResponse(batch);
                var tokens = response.getMetadata().getUsage();
                usage.report(ModelCallUsage.EMBEDDING, baseUrl, modelName,
                        tokens.getPromptTokens() == null ? 0 : tokens.getPromptTokens(), 0);
                return response.getResults().stream().map(embedding -> embedding.getOutput()).toList();
            } catch (RuntimeException e) {
                if (round >= waits.size() || !rateLimited(e)) {
                    throw e;
                }
                log.info("event=embedding_rate_limited model={} round={} wait={}", modelName, round + 1,
                        waits.get(round));
                pause(waits.get(round), e);
            }
        }
    }

    private static boolean rateLimited(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof RateLimitException) {
                return true;
            }
        }
        return false;
    }

    private static void pause(Duration wait, RuntimeException rateLimited) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            // 正在停机：不再等，文档按模型暂时不可用失败，稍后自动重试。
            Thread.currentThread().interrupt();
            throw rateLimited;
        }
    }
}
