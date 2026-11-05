package com.liming.rag.settings.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.ModelRoute;
import com.liming.rag.ai.ModelUsage;
import com.liming.rag.settings.application.model.ModelConnectionException;
import com.liming.rag.settings.application.model.ModelConnector;
import com.liming.rag.settings.application.model.ModelEndpoint;
import com.liming.rag.settings.application.model.ModelPurpose;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 适配器把 settings 自己的类型和 rag-ai 的类型互相转换；rag-ai 的异常转成本上下文的异常，错误码不变（#158）。 */
class AiModelConnectorTest {

    private final FakeProbe probe = new FakeProbe();
    private final AiModelConnector connector = new AiModelConnector(probe);

    @Test
    @DisplayName("每个用途都对应 rag-ai 的同名用途，环境变量里的配置转成 settings 的 ModelEndpoint")
    void mapsPurposesAndEndpoints() {
        for (var purpose : ModelPurpose.values()) {
            assertThat(connector.environment(purpose))
                    .isEqualTo(new ModelEndpoint("https://env.example/v1", "sk-env", purpose.name()));
        }
        assertThat(connector.embedding()).isEqualTo(new ModelEndpoint("https://env.example/v1", null, "bge-m3"));
        assertThat(connector.embeddingDimensions()).isEqualTo(1024);
    }

    @Test
    @DisplayName("连通测试把用途和地址交给 rag-ai，结果转成 settings 的 ProbeResult，失败说明原样带回")
    void probeConvertsBothWays() {
        var ok = connector.probe(ModelPurpose.RERANK, new ModelEndpoint("https://x/v1", "sk-1", "bge"));
        probe.failWith = "密钥无效";
        var failed = connector.probe(ModelPurpose.ANSWER, new ModelEndpoint("https://x/v1", "sk-1", "chat"));

        assertThat(probe.probed).containsExactly(
                ModelUsage.RERANK + " https://x/v1 bge sk-1", ModelUsage.ANSWER + " https://x/v1 chat sk-1");
        assertThat(ok).isEqualTo(new ModelConnector.ProbeResult(true, 300L, "连通正常"));
        assertThat(failed).isEqualTo(new ModelConnector.ProbeResult(false, null, "密钥无效"));
    }

    @Test
    @DisplayName("读模型列表失败：转成本上下文的异常，错误码仍是 MODEL_UNAVAILABLE，说明不变")
    void modelsFailureKeepsErrorCode() {
        probe.modelsFailure = new ModelCallException("服务商不提供模型列表");

        assertThatThrownBy(() -> connector.models("https://x/v1", "sk-1"))
                .isInstanceOf(ModelConnectionException.class)
                .isNotInstanceOf(ModelCallException.class)
                .hasMessage("服务商不提供模型列表")
                .extracting(e -> ((ModelConnectionException) e).code())
                .isEqualTo("MODEL_UNAVAILABLE");
        probe.modelsFailure = null;
        assertThat(connector.models("https://x/v1", "sk-1")).containsExactly("m1", "m2");
    }

    private static final class FakeProbe implements ModelProbe {

        final List<String> probed = new ArrayList<>();
        String failWith;
        ModelCallException modelsFailure;

        @Override
        public ModelRoute environment(ModelUsage usage) {
            return new ModelRoute("https://env.example/v1", "sk-env", usage.name());
        }

        @Override
        public ModelRoute embedding() {
            return new ModelRoute("https://env.example/v1", null, "bge-m3");
        }

        @Override
        public int embeddingDimensions() {
            return 1024;
        }

        @Override
        public ProbeResult probe(ModelUsage usage, ModelRoute route) {
            probed.add(usage + " " + route.baseUrl() + " " + route.model() + " " + route.apiKey());
            return failWith == null ? new ProbeResult(true, 300L, "连通正常") : new ProbeResult(false, null, failWith);
        }

        @Override
        public List<String> models(String baseUrl, String apiKey) {
            if (modelsFailure != null) {
                throw modelsFailure;
            }
            return List.of("m1", "m2");
        }
    }
}
