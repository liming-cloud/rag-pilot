package com.liming.rag.ai.common;

import com.liming.rag.ai.UsageRecording;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.ObjectProvider;

/** 把每次调用的用量交给 {@link UsageRecording}（#169）。没有实现时什么也不做；记录失败只写日志，不让一次统计拖垮一次问答。 */
public final class UsageReports {

    private static final Logger log = LoggerFactory.getLogger(UsageReports.class);

    /** 当前线程上的用量归属（{@link com.liming.rag.ai.UsageAttribution}）；没有时按各次调用自己的用途记。 */
    private static final ThreadLocal<@Nullable String> ATTRIBUTION = new ThreadLocal<>();

    private final ObjectProvider<UsageRecording> recording;

    public UsageReports(ObjectProvider<UsageRecording> recording) {
        this.recording = recording;
    }

    public void report(String purpose, ModelRoute route, long inputTokens, long outputTokens) {
        report(purpose, route.baseUrl(), route.model(), inputTokens, outputTokens);
    }

    public void report(String purpose, String baseUrl, String model, long inputTokens, long outputTokens) {
        var attributed = ATTRIBUTION.get();
        record(attributed == null ? purpose : attributed, baseUrl, model, inputTokens, outputTokens);
    }

    /** 流式生成的用量在别的线程上报：{@code attributed} 是发起时取的归属（{@link #attribution()}），为 null 时用 {@code usage}。 */
    public void report(@Nullable String attributed, ModelUsage usage, ModelRoute route, @Nullable Usage tokens) {
        var purpose = attributed == null ? usage.name() : attributed;
        if (tokens == null) {
            record(purpose, route.baseUrl(), route.model(), 0, 0);
        } else {
            record(purpose, route.baseUrl(), route.model(), count(tokens.getPromptTokens()),
                    count(tokens.getCompletionTokens()));
        }
    }

    /** 当前线程上的用量归属，没有时为 null。 */
    public static @Nullable String attribution() {
        return ATTRIBUTION.get();
    }

    /** 在 {@code purpose} 的归属下做 {@code work}，结束后恢复原来的归属。 */
    static <T> T attributed(@Nullable String purpose, Supplier<T> work) {
        var previous = ATTRIBUTION.get();
        ATTRIBUTION.set(purpose);
        try {
            return work.get();
        } finally {
            ATTRIBUTION.set(previous);
        }
    }

    private void record(String purpose, String baseUrl, String model, long inputTokens, long outputTokens) {
        var target = recording.getIfAvailable();
        if (target == null) {
            return;
        }
        try {
            target.record(new ModelCallUsage(purpose, baseUrl, model, Math.max(0, inputTokens), Math.max(0, outputTokens)));
        } catch (RuntimeException e) {
            log.warn("event=model_usage_record_failed purpose={} model={} error={}", purpose, model, e.toString());
        }
    }

    /** Spring AI 的用量里 token 数可能为 null（服务商没返回）。 */
    public void report(ModelUsage usage, ModelRoute route, Usage tokens) {
        report(usage.name(), route, count(tokens.getPromptTokens()), count(tokens.getCompletionTokens()));
    }

    private static long count(Integer tokens) {
        return tokens == null ? 0 : tokens;
    }
}
