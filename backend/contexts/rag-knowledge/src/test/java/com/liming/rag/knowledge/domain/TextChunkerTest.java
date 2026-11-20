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
    @DisplayName("大表格按整行切成行组：每块以表头行开头、位置是所在章节，行不被切开，行组之间不重叠（#225）")
    void largeTablesAreSplitIntoRowGroupsWithHeader() {
        var header = TextChunker.TABLE_HEADER + "门店 | 状态 | 备注";
        var rows = IntStream.range(0, 60)
                .mapToObj(i -> "门店：A" + i + "；状态：营业；备注：早班八点开门，晚班十点闭店")
                .toList();
        var text = "## 门店状态\n\n下表是各门店的营业状态。\n\n" + header + "\n" + String.join("\n", rows) + "\n";
        var chunks = new TextChunker(120, 20).chunk("运营周报", text);

        var tableChunks = chunks.stream().filter(chunk -> chunk.content().startsWith(header)).toList();
        assertThat(tableChunks.size()).isGreaterThan(5);
        assertThat(tableChunks).allSatisfy(chunk -> {
            assertThat(chunk.headingPath()).isEqualTo("运营周报 > 门店状态");
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(120);
        });
        var tableRows = tableChunks.stream().flatMap(chunk -> chunk.content().lines().skip(1)).toList();
        assertThat(tableRows).containsExactlyElementsOf(rows);
        assertThat(chunks.getFirst().content()).isEqualTo("下表是各门店的营业状态。");
    }

    @Test
    @DisplayName("放得下的小表格不拆，和前后正文一起装箱（#225）")
    void smallTablesStayWhole() {
        var text = "交班规范。\n\n" + TextChunker.TABLE_HEADER + "班次 | 时间\n班次：早班；时间：8:00\n班次：晚班；时间：22:00\n";
        var chunks = new TextChunker(120, 20).chunk("交班", text);

        assertThat(chunks).singleElement().extracting(Chunk::content).isEqualTo(
                "交班规范。\n" + TextChunker.TABLE_HEADER + "班次 | 时间\n班次：早班；时间：8:00\n班次：晚班；时间：22:00");
    }

    @Test
    @DisplayName("长章节先按行切；重叠从一行的开头开始，不从半句开始（#225）")
    void overlapStartsAtALine() {
        var lines = IntStream.range(0, 40).mapToObj(i -> "第" + i + "条：交班前清点备用金。").toList();
        var chunks = new TextChunker(60, 20).chunk("收银规范", String.join("\n", lines));

        assertThat(chunks.size()).isGreaterThan(5);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().lines())
                .allSatisfy(line -> assertThat(lines).contains(line)));
        assertThat(chunks.get(1).content().lines().findFirst().orElseThrow())
                .isIn(chunks.get(0).content().lines().toList());
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
