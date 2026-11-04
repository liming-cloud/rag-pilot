package com.liming.rag.knowledge.application.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeadingChunkerTest {

    @Test
    @DisplayName("按标题切块，章节路径以文件名（去掉扩展名）开头，带上序号和 token 数")
    void chunksWithHeadingPath() {
        var chunks = new HeadingChunker().chunk("收银规范.md",
                "# 收银\n\n## 交班\n\n交班时清点备用金。\n\n## 开店\n\n开店前检查收银机。");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().content()).contains("交班时清点备用金");
        assertThat(chunks.getFirst().ordinal()).isZero();
        assertThat(chunks.getFirst().headingPath()).isEqualTo("收银规范 > 收银 > 交班");
        assertThat(chunks.getFirst().tokenCount()).isPositive();
        assertThat(chunks.get(1).ordinal()).isEqualTo(1);
    }
}
