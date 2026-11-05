package com.liming.rag.ai;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型调用失败时留一行日志再转成 {@link ModelCallException}（#145 C1）。只记用途、模型和异常类型，
 * 不记提示词、回答、资料和密钥；调用耗时、次数由 Spring AI 的观测进 {@code /actuator/metrics}。
 */
final class ModelCallFailures {

    private static final Logger log = LoggerFactory.getLogger(ModelCallFailures.class);

    private ModelCallFailures() {
    }

    /**
     * @param usage 用途：answer、light、rewrite、embedding、rerank 等
     * @param cause 底层异常；没有时为 null（如服务商返回了错误的状态码），日志里记 {@code message}
     */
    static ModelCallException failed(String usage, String model, String message, @Nullable Throwable cause) {
        log.warn("event=model_call_failed usage={} model={} error={}", usage, model,
                cause == null ? message : describe(cause));
        return cause == null ? new ModelCallException(message) : new ModelCallException(message, cause);
    }

    /** 最里层异常的类型，超时、连不上、限流一眼能看出来。 */
    private static String describe(Throwable cause) {
        var root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
