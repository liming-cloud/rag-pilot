package com.liming.rag.settings.application;

import org.jspecify.annotations.Nullable;

/**
 * 在界面添加的模型服务商（OpenAI 兼容接口）。密钥只以密文存放，界面只显示后 4 位。
 *
 * @param apiKeyCipher 加密后的密钥；不需要密钥的服务为 null
 */
public record ModelProvider(@Nullable Long id, String name, String baseUrl, byte @Nullable [] apiKeyCipher,
        @Nullable String apiKeyLast4) {
}
