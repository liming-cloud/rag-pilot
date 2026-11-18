package com.liming.rag.ai.common;

import org.springframework.modulith.NamedInterface;

/**
 * 一次模型调用的用量。{@code purpose} 是 {@link ModelUsage} 的名字，嵌入（不能单独配置）用 {@link #EMBEDDING}；
 * {@code baseUrl} 标明服务商；服务商没返回用量时 token 记 0。
 */
@NamedInterface("common")
public record ModelCallUsage(String purpose, String baseUrl, String model, long inputTokens, long outputTokens) {

    public static final String EMBEDDING = "EMBEDDING";
    /** 知识质量的试问和运行（#224）：期间的回答、重排、向量化都记在这里（{@link com.liming.rag.ai.UsageAttribution}）。 */
    public static final String EVALUATION = "EVALUATION";
}
