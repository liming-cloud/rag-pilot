package com.liming.rag.ai;

/**
 * 一次模型调用打到哪里：OpenAI 兼容地址（含 /v1）、密钥（可以为空，如内网自建的服务）、模型名。
 * 密钥不出现在 {@link #toString()} 里。
 */
public record ModelRoute(String baseUrl, String apiKey, String model) {

    public ModelRoute {
        baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        model = model == null ? "" : model.strip();
    }

    @Override
    public String toString() {
        return "ModelRoute[baseUrl=" + baseUrl + ", model=" + model + "]";
    }
}
