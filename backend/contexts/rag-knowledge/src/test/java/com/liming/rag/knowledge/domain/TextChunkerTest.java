package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    @DisplayName("Markdown 标题成为分块位置，分块不跨章节")
    void headingsBecomePaths() {
        var text = """
                # 门店商品效期管理规范

                ## 4.2 临期商品处理

                临期商品应在保质期截止前三天下架。

                ## 4.3 损耗登记

                下架后在系统登记损耗。
                """;
        var chunks = new TextChunker().chunk("效期规范", text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("效期规范 > 门店商品效期管理规范 > 4.2 临期商品处理");
        assertThat(chunks.get(0).content()).isEqualTo("临期商品应在保质期截止前三天下架。");
        assertThat(chunks.get(1).headingPath()).isEqualTo("效期规范 > 门店商品效期管理规范 > 4.3 损耗登记");
        assertThat(chunks).extracting(Chunk::ordinal).containsExactly(0, 1);
    }

    @Test
    @DisplayName("长章节按上限切成多块，相邻分块有重叠")
    void longSectionsAreSplitWithOverlap() {
        var paragraphs = IntStream.range(0, 40)
                .mapToObj(i -> "第" + i + "条：收银员交班前需要完成现金盘点并签字确认，差异超过十元时上报店长。")
                .collect(Collectors.joining("\n\n"));
        var chunks = new TextChunker(120, 20).chunk("收银规范", paragraphs);

        assertThat(chunks.size()).isGreaterThan(5);
        for (var chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(120 + 20 + 40);
        }
        var firstTail = chunks.get(0).content().substring(chunks.get(0).content().length() - 10);
        assertThat(chunks.get(1).content()).contains(firstTail);
    }

    @Test
    @DisplayName("超长的单个段落按句子切开")
    void longParagraphIsSplitBySentence() {
        var sentence = "临期商品应在保质期截止前三天下架并登记损耗。";
        var chunks = new TextChunker(50, 10).chunk("规范", sentence.repeat(10));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(50 + 10 + 25));
    }

    @Test
    @DisplayName("没有可见文字时不产生分块")
    void blankTextHasNoChunks() {
        assertThat(new TextChunker().chunk("空文档", "\uFEFF\n\n   \n")).isEmpty();
    }

    @Test
    @DisplayName("token 估算：汉字按 1 个，英文约 4 个字符 1 个")
    void tokenEstimate() {
        assertThat(TokenEstimator.estimate("临期商品")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("POS system")).isEqualTo(3);
        assertThat(TokenEstimator.estimate("下架，")).isEqualTo(3);
    }
}
