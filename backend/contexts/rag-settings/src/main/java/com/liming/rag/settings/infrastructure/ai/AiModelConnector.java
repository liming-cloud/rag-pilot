package com.liming.rag.settings.infrastructure.ai;

import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelRoute;
import com.liming.rag.ai.common.ModelUsage;
import com.liming.rag.settings.application.model.ModelConnectionException;
import com.liming.rag.settings.application.model.ModelConnector;
import com.liming.rag.settings.application.model.ModelEndpoint;
import com.liming.rag.settings.application.model.ModelPurpose;
import java.util.List;
import org.springframework.stereotype.Component;

/** 用 rag-ai 的 {@link ModelProbe} 实现模型管理的端口（D-054）：在这里把 settings 的类型和 rag-ai 的类型互相转换。 */
@Component
class AiModelConnector implements ModelConnector {

    private final ModelProbe probe;

    AiModelConnector(ModelProbe probe) {
        this.probe = probe;
    }

    @Override
    public ModelEndpoint environment(ModelPurpose purpose) {
        return endpoint(probe.environment(usage(purpose)));
    }

    @Override
    public ModelEndpoint embedding() {
        return endpoint(probe.embedding());
    }

    @Override
    public int embeddingDimensions() {
        return probe.embeddingDimensions();
    }

    @Override
    public ProbeResult probe(ModelPurpose purpose, ModelEndpoint endpoint) {
        var result = probe.probe(usage(purpose), route(endpoint));
        return new ProbeResult(result.ok(), result.latencyMs(), result.message());
    }

    @Override
    public List<String> models(String baseUrl, String apiKey) {
        try {
            return probe.models(baseUrl, apiKey);
        } catch (ModelCallException e) {
            throw new ModelConnectionException(e.getMessage(), e);
        }
    }

    static ModelUsage usage(ModelPurpose purpose) {
        return ModelUsage.valueOf(purpose.name());
    }

    static ModelRoute route(ModelEndpoint endpoint) {
        return new ModelRoute(endpoint.baseUrl(), endpoint.apiKey(), endpoint.model());
    }

    private static ModelEndpoint endpoint(ModelRoute route) {
        return new ModelEndpoint(route.baseUrl(), route.apiKey(), route.model());
    }
}
