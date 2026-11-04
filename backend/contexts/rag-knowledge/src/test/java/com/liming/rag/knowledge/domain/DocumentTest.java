package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.shared.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTest {

    private static Document received() {
        return Document.received(new com.liming.rag.shared.TenantId(1), 10,
                new UploadedFile("规范.md", "text/markdown", "a".repeat(64), 128), null, Validity.ALWAYS);
    }

    @Test
    @DisplayName("解析中的文档可以变为可检索")
    void parsingToReady() {
        var document = received();
        document.markReady(3);
        assertThat(document.status()).isEqualTo(DocumentStatus.READY);
        assertThat(document.chunkCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("失败必须给出原因，且已完成的文档不能再改状态")
    void failureRules() {
        assertThatThrownBy(() -> received().markFailed(" ")).isInstanceOf(IllegalArgumentException.class);

        var document = received();
        document.markFailed("文件不是 UTF-8 编码");
        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThatThrownBy(() -> document.markReady(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("可检索的文档至少有一个分块")
    void readyNeedsChunks() {
        assertThatThrownBy(() -> received().markReady(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("模型暂时不可用导致的失败：10 分钟后自动重试一次，可以重新处理；重试后再失败不再自动重试")
    void modelUnavailableIsRetryable() {
        var now = java.time.OffsetDateTime.parse("2026-11-04T10:00:00Z");
        var document = received();
        document.markModelUnavailable("向量化失败：嵌入模型服务暂时不可用，请稍后重试", now);
        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.reprocessable()).isTrue();
        assertThat(document.retryAt()).isEqualTo(now.plusMinutes(10));

        document.reprocess();
        assertThat(document.status()).isEqualTo(DocumentStatus.PARSING);
        assertThat(document.error()).isNull();
        assertThat(document.retryAt()).isNull();
        assertThat(document.attempts()).isEqualTo(2);

        document.markModelUnavailable("向量化失败：嵌入模型服务暂时不可用，请稍后重试", now.plusMinutes(11));
        assertThat(document.retryAt()).as("只自动重试一次").isNull();
        assertThat(document.reprocessable()).as("仍可手动重新处理").isTrue();
    }

    @Test
    @DisplayName("文件内容的问题不能重新处理，要改好文件重新上传；解析中、已就绪的也不能")
    void contentFailureIsNotReprocessable() {
        var document = received();
        assertThat(document.reprocessable()).isFalse();
        assertThatThrownBy(document::reprocess).isInstanceOf(IllegalStateException.class);

        document.markFailed("文件里没有可检索的文字");
        assertThat(document.reprocessable()).isFalse();
        assertThat(document.retryAt()).isNull();
        assertThatThrownBy(document::reprocess).isInstanceOf(IllegalStateException.class);
    }
}
