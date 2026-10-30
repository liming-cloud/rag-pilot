package com.liming.rag.answering.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 问答配置（{@code rag.answering}）。取值在构造时校验，非法时应用启动失败，不带着错误的门槛运行。
 *
 * @param candidates            检索候选数
 * @param evidenceLimit         送进提示词的证据数，不多于候选数
 * @param minRelevance          重排相关度门槛，低于它的候选不作为证据（D-032）
 * @param rewriteOnLowRelevance 都低于门槛时是否改写问题再检索一次（D-022）
 * @param rewriteMinRelevance   改写前最高分低于这个下限时不改写，直接拒答（D-023）
 * @param rewriteTimeout        改写超过这个时限就放弃
 */
@ConfigurationProperties("rag.answering")
public record AnsweringProperties(
        @DefaultValue("30") int candidates,
        @DefaultValue("8") int evidenceLimit,
        @DefaultValue("0.2") double minRelevance,
        @DefaultValue("true") boolean rewriteOnLowRelevance,
        @DefaultValue("0.03") double rewriteMinRelevance,
        @DefaultValue("3s") Duration rewriteTimeout) {

    public AnsweringProperties {
        require(candidates >= 1 && candidates <= 200, "rag.answering.candidates 必须在 1 到 200 之间");
        require(evidenceLimit >= 1 && evidenceLimit <= candidates,
                "rag.answering.evidence-limit 必须在 1 到 candidates 之间");
        require(minRelevance >= 0 && minRelevance <= 1, "rag.answering.min-relevance 必须在 0 到 1 之间");
        // 不要求改写下限 ≤ 门槛：评测校准门槛时会把 min-relevance 调到 0 附近（eval 工作流的输入）。
        require(rewriteMinRelevance >= 0 && rewriteMinRelevance <= 1,
                "rag.answering.rewrite-min-relevance 必须在 0 到 1 之间");
        require(rewriteTimeout != null && rewriteTimeout.isPositive(), "rag.answering.rewrite-timeout 必须大于 0");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
