package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 上传的文件能不能收下（#136）：类型按扩展名（不区分大小写），非空、不超过 50 MB，文件名不超过 255 个字符。 */
class UploadedFileTest {

    private static final byte[] TEXT = "收银员交班时要清点备用金。".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("按扩展名定类型，算内容的 SHA-256；同样的内容摘要相同")
    void acceptsSupportedFiles() {
        var file = UploadedFile.of("收银规范.TXT", TEXT);

        assertThat(file.mediaType()).isEqualTo("text/plain");
        assertThat(file.sizeBytes()).isEqualTo(TEXT.length);
        assertThat(file.sha256()).hasSize(64).isEqualTo(UploadedFile.of("改了名字.md", TEXT).sha256());
        assertThat(UploadedFile.of("a.xlsx", TEXT).mediaType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(UploadedFile.of("课件.PPTX", TEXT).mediaType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    @DisplayName("类型不支持单独报出；空文件、超过 50 MB、文件名太长是不合格")
    void rejectsBadFiles() {
        assertThatThrownBy(() -> UploadedFile.of("旧版.doc", TEXT)).isInstanceOf(UploadedFile.UnsupportedTypeException.class)
                .hasMessageContaining("PowerPoint（.pptx）");
        assertThatThrownBy(() -> UploadedFile.of(null, TEXT)).isInstanceOf(UploadedFile.UnsupportedTypeException.class)
                .hasFieldOrPropertyWithValue("code", "KNOWLEDGE_UNSUPPORTED_TYPE");
        assertThatThrownBy(() -> UploadedFile.of("空.txt", new byte[0])).isInstanceOf(KnowledgeRuleException.class)
                .isNotInstanceOf(UploadedFile.UnsupportedTypeException.class).hasMessage("文件是空的")
                .hasFieldOrPropertyWithValue("code", "KNOWLEDGE_INVALID");
        assertThatThrownBy(() -> UploadedFile.of("大.txt", new byte[(int) UploadedFile.MAX_SIZE_BYTES + 1]))
                .hasMessage("单个文件不能超过 50 MB；课件可先压缩图片或另存为 PDF");
        assertThatThrownBy(() -> UploadedFile.of("长".repeat(252) + ".txt", TEXT))
                .hasMessage("文件名不能超过 255 个字符");
    }
}
