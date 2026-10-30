package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationAskServiceTest {

    @Test
    @DisplayName("寒暄回复：一句短话照用；太长、带数字、为空或是 NO_ANSWER 时用固定的一句，保证不含事实")
    void acceptsOnlyHarmlessSmallTalk() {
        assertThat(ConversationAskService.acceptSmallTalk(" 不客气，随时问我。 ")).isEqualTo("不客气，随时问我。");
        assertThat(ConversationAskService.acceptSmallTalk("不客气。顺便说一句，冷柜要保持 2–6℃。"))
                .isEqualTo(ConversationAskService.SMALL_TALK_FALLBACK);
        assertThat(ConversationAskService.acceptSmallTalk("好".repeat(31))).isEqualTo(ConversationAskService.SMALL_TALK_FALLBACK);
        assertThat(ConversationAskService.acceptSmallTalk("")).isEqualTo(ConversationAskService.SMALL_TALK_FALLBACK);
        assertThat(ConversationAskService.acceptSmallTalk("NO_ANSWER")).isEqualTo(ConversationAskService.SMALL_TALK_FALLBACK);
    }

    @Test
    @DisplayName("生成的标题：去掉引号和句末标点；空的、太长的、NO_ANSWER 或带引用标记的不采用")
    void acceptsOnlyCleanTitles() {
        assertThat(ConversationAskService.acceptTitle("“临期商品处理。”")).contains("临期商品处理");
        assertThat(ConversationAskService.acceptTitle("长".repeat(17))).isEmpty();
        assertThat(ConversationAskService.acceptTitle("NO_ANSWER")).isEmpty();
        assertThat(ConversationAskService.acceptTitle("临期[1]")).isEmpty();
        assertThat(ConversationAskService.acceptTitle("  ")).isEmpty();
    }
}
