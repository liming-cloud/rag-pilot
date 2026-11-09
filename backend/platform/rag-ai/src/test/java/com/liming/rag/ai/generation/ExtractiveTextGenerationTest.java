package com.liming.rag.ai.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractiveTextGenerationTest {

    @Test
    @DisplayName("本地对话：摘第 1 条资料的第一句并标注 [1]，没有资料时拒答")
    void extractiveChatCitesFirstEvidence() {
        var chat = new ExtractiveTextGeneration();

        assertThat(chat.complete("", "资料：\n[1] 来源：商品陈列.txt\n放在 03 号货架。补货先移临期。\n\n问题：在哪"))
                .isEqualTo("放在 03 号货架。[1]");
        assertThat(chat.complete("", "问题：在哪")).isEqualTo("NO_ANSWER");
    }
}
