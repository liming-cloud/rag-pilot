package com.liming.rag.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.DocumentReaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class HeadingChunkerTest {

    @Test
    @DisplayName("按标题切块，章节路径以文件名（去掉扩展名）开头，序号、token 数写进元数据")
    void chunksWithMetadata() {
        var source = new Document("# 收银\n\n## 交班\n\n交班时清点备用金。\n\n## 开店\n\n开店前检查收银机。",
                Map.of(DocumentReaders.FILENAME, "收银规范.md"));

        var chunks = new HeadingChunker().apply(List.of(source));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().getText()).contains("交班时清点备用金");
        assertThat(chunks.getFirst().getMetadata())
                .containsEntry(ChunkMetadata.ORDINAL, 0)
                .containsEntry(ChunkMetadata.HEADING_PATH, "收银规范 > 收银 > 交班");
        assertThat((Integer) chunks.getFirst().getMetadata().get(ChunkMetadata.TOKEN_COUNT)).isPositive();
        assertThat(chunks.get(1).getMetadata()).containsEntry(ChunkMetadata.ORDINAL, 1);
    }
}
