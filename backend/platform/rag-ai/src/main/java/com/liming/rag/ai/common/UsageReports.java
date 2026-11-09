package com.liming.rag.ai.common;

import com.liming.rag.ai.UsageRecording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.ObjectProvider;

/** 把每次调用的用量交给 {@link UsageRecording}（#169）。没有实现时什么也不做；记录失败只写日志，不让一次统计拖垮一次问答。 */
public final class UsageReports {

    private static final Logger log = LoggerFactory.getLogger(UsageReports.class);

    private final ObjectProvider<UsageRecording> recording;

    public UsageReports(ObjectProvider<UsageRecording> recording) {
        this.recording = recording;
    }

    public void report(String purpose, ModelRoute route, long inputTokens, long outputTokens) {
        report(purpose, route.baseUrl(), route.model(), inputTokens, outputTokens);
    }

    public void report(String purpose, String baseUrl, String model, long inputTokens, long outputTokens) {
        var target = recording.getIfAvailable();
        if (target == null) {
            return;
        }
        try {
            target.record(new ModelCallUsage(purpose, baseUrl, model, Math.max(0, inputTokens), Math.max(0, outputTokens)));
        } catch (RuntimeException e) {
            log.warn("event=model_usage_record_failed purpose={} model={} 原因={}", purpose, model, e.toString());
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
