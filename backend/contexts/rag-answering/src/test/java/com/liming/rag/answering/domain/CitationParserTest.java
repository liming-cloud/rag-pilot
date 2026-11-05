package com.liming.rag.answering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CitationParserTest {

    @Test
    @DisplayName("保留有效序号并按首次出现排序，删掉越界的序号")
    void keepsValidMarkersOnly() {
        var parsed = CitationParser.parse("先下架[2]，再登记[1][9]。店长签字[2][0]。", 3);

        assertThat(parsed.cited()).containsExactly(2, 1);
        assertThat(parsed.text()).isEqualTo("先下架[2]，再登记[1]。店长签字[2]。");
    }

    @Test
    @DisplayName("全角括号、【n】、[资料n]、[1,2] 等变体统一成 [n]")
    void normalizesVariants() {
        var parsed = CitationParser.parse("两周一换【1】，由店长定［资料2］。见 [1，3]、[来源 2]。", 3);

        assertThat(parsed.cited()).containsExactly(1, 2, 3);
        assertThat(parsed.text()).isEqualTo("两周一换[1]，由店长定[2]。见 [1][3]、[2]。");
    }

    @Test
    @DisplayName("没有任何标记时引用为空")
    void noMarkers() {
        assertThat(CitationParser.parse("可以直接下架。", 3).cited()).isEmpty();
    }
}
