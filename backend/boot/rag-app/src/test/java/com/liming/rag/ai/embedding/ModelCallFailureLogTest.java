package com.liming.rag.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 模型调用失败留一行日志（#145 C1）：用途、模型、异常类型，不带密钥和送去的文本。
 * rag-ai 没有日志实现和输出捕获，放在 rag-app 里测，和被测类同包（包内可见）。
 */
@ExtendWith(OutputCaptureExtension.class)
class ModelCallFailureLogTest {

    @Test
    @DisplayName("嵌入模型连不上：抛出 ModelCallException，日志记下 model_call_failed、模型名和异常类型，没有密钥和原文")
    void logsEmbeddingFailure(CapturedOutput output) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var properties = new AiProperties("http://127.0.0.1:" + port + "/v1", "sk-secret-test-key",
                Duration.ofSeconds(2), new AiProperties.Embedding("openai", "BAAI/bge-m3", 16), null, null);
        var client = new SpringAiEmbeddingClient(properties, io.micrometer.observation.ObservationRegistry.NOOP);

        assertThatThrownBy(() -> client.embed(List.of("保险柜密码是多少"))).isInstanceOf(ModelCallException.class);

        assertThat(output.getAll()).containsPattern("event=model_call_failed usage=embedding model=BAAI/bge-m3 error=\\w+")
                .doesNotContain("sk-secret-test-key").doesNotContain("保险柜密码是多少");
    }
}
