package com.liming.rag.ai.generation;

import com.liming.rag.ai.TextGeneration;
import java.time.Duration;

/** {@link TextGeneration#fixed} 的实现入口：根包接口不能直接访问包内私有的 {@code FixedTextGeneration}。 */
public final class FixedTextGenerations {

    private FixedTextGenerations() {
    }

    public static TextGeneration create(String baseUrl, String apiKey, String model, Duration timeout, int maxTokens) {
        return new FixedTextGeneration(baseUrl, apiKey, model, timeout, maxTokens);
    }
}
