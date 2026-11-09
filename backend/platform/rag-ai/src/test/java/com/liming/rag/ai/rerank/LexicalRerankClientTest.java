package com.liming.rag.ai.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LexicalRerankClientTest {

    @Test
    @DisplayName("本地重排：问题词组出现得越多分数越高，无关文本为 0")
    void lexicalRerankOrdersByOverlap() {
        var scores = new LexicalRerankClient().score("青柚气泡水在哪个货架",
                List.of("青柚气泡水陈列在 03 号货架。", "收银员交班要清点备用金。"));

        assertThat(scores.get(0)).isGreaterThan(0.5);
        assertThat(scores.get(1)).isZero();
    }
}
