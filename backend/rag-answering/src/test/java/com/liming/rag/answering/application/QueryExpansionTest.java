package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 行业词表扩展检索用的问题（D-045）。 */
class QueryExpansionTest {

    private final QueryExpansion expansion = new QueryExpansion(TestSettings.RETAIL_PACK.vocabulary());

    @Test
    @DisplayName("“鲜食下架-口语”：问题里有便当、饭团，检索时追加“鲜食”，只加一次")
    void appendsCategory() {
        assertThat(expansion.expand("便当饭团晚上几点得撤下来啊")).isEqualTo("便当饭团晚上几点得撤下来啊 鲜食");
    }

    @Test
    @DisplayName("已经提到上位词、或没有命中词表时不改问题；命中几组就追加几个")
    void leavesOtherQuestionsAlone() {
        assertThat(expansion.expand("鲜食里的便当几点下架")).isEqualTo("鲜食里的便当几点下架");
        assertThat(expansion.expand("年假怎么请")).isEqualTo("年假怎么请");
        assertThat(expansion.expand("坏货和便当怎么处理")).isEqualTo("坏货和便当怎么处理 鲜食 报损");
        assertThat(new QueryExpansion(List.of()).expand("便当")).isEqualTo("便当");
    }
}
