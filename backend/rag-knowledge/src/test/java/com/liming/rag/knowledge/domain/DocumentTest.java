package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.shared.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTest {

    private static Document received() {
        return Document.received(new TenantId(1), 10, "规范.md", "text/markdown", "a".repeat(64), 128);
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
}
