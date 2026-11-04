package com.liming.rag.settings.application.pack;

import com.liming.rag.settings.application.SettingKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 行业包文件的读取和校验（D-045）。 */
class IndustryPackFileTest {

    @Test
    @DisplayName("新零售行业包：提示词、话术、文案、推荐问题、词表都读得到，“便当”归在“鲜食”下")
    void loadsRetail() {
        var file = IndustryPackFile.load("retail");
        var pack = file.pack("retail");

        assertThat(pack.name()).isEqualTo("新零售");
        assertThat(pack.answerRole()).isEqualTo("你是连锁门店的运营助手，回答门店员工的问题。");
        assertThat(pack.citationExample()).isEqualTo("临期商品提前三天下架[2]。");
        assertThat(pack.rewriteRole()).isEqualTo("你负责把连锁门店员工的提问改写成便于检索门店制度文件的规范问题。");
        assertThat(pack.rewriteHints()).containsExactly("提到具体商品时，在后面补上它所属的商品类别。");
        assertThat(pack.vocabulary()).anySatisfy(term -> {
            assertThat(term.term()).isEqualTo("鲜食");
            assertThat(term.members()).contains("便当", "饭团");
        });
        assertThat(file.value(SettingKey.REFUSAL_MESSAGE)).contains(
                List.of("知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。"));
        assertThat(file.value(SettingKey.SUGGESTED_QUESTIONS).orElseThrow()).hasSizeBetween(1, 4);
        assertThat(file.value(SettingKey.BRAND_NAME)).as("品牌不归行业包").isEmpty();
        assertThat(file.markers()).contains("门店", "鲜食");
    }

    @Test
    @DisplayName("行业包没写的项用内核默认值，内核默认值里没有行业内容")
    void fallsBackToKernelDefaults() {
        var file = IndustryPackFile.load("bare");
        var pack = file.pack("bare");

        assertThat(pack.answerRole()).isEqualTo(KernelDefaults.ANSWER_ROLE);
        assertThat(pack.citationExample()).isEqualTo(KernelDefaults.CITATION_EXAMPLE);
        assertThat(pack.rewriteRole()).isEqualTo(KernelDefaults.REWRITE_ROLE);
        assertThat(pack.rewriteHints()).isEmpty();
        assertThat(pack.vocabulary()).isEmpty();
        assertThat(file.value(SettingKey.REFUSAL_MESSAGE)).isEmpty();
        var retailMarkers = IndustryPackFile.load("retail").markers();
        assertThat(List.of(KernelDefaults.ANSWER_ROLE, KernelDefaults.CITATION_EXAMPLE, KernelDefaults.REWRITE_ROLE,
                KernelDefaults.REFUSAL_MESSAGE, KernelDefaults.CHAT_EMPTY_HINT))
                .allSatisfy(text -> assertThat(retailMarkers).noneMatch(text::contains));
    }

    @Test
    @DisplayName("找不到或不合格的行业包启动失败，说明是哪个包、哪一项")
    void rejectsMissingOrBrokenPacks() {
        assertThatThrownBy(() -> IndustryPackFile.load("nowhere"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("找不到行业包 nowhere").hasMessageContaining("RAG_INDUSTRY_PACK");
        assertThatThrownBy(() -> IndustryPackFile.load("broken"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行业包 broken").hasMessageContaining("citation-example");
    }
}
