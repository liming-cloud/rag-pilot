package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalModelsTest {

    @Test
    @DisplayName("本地重排：问题词组出现得越多分数越高，无关文本为 0")
    void lexicalRerankOrdersByOverlap() {
        var scores = new LexicalRerankClient().score("青柚气泡水在哪个货架",
                List.of("青柚气泡水陈列在 03 号货架。", "收银员交班要清点备用金。"));

        assertThat(scores.get(0)).isGreaterThan(0.5);
        assertThat(scores.get(1)).isZero();
    }

    @Test
    @DisplayName("本地对话：摘第 1 条资料的第一句并标注 [1]，没有资料时拒答")
    void extractiveChatCitesFirstEvidence() {
        var chat = new ExtractiveChatGateway();

        assertThat(chat.complete("", "资料：\n[1] 来源：商品陈列.txt\n放在 03 号货架。补货先移临期。\n\n问题：在哪"))
                .isEqualTo("放在 03 号货架。[1]");
        assertThat(chat.complete("", "问题：在哪")).isEqualTo("NO_ANSWER");
    }
}
