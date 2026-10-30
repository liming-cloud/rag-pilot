package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;

class AnswerComponentsTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId USER = new UserId(7);
    private static final SearchHit SHELF = new SearchHit(11, 1, "商品陈列.txt", "饮料", "青柚气泡水在 03 号货架。", 0.03);
    private static final SearchHit CASH = new SearchHit(12, 2, "收银规范.txt", "", "交班清点备用金。", 0.02);

    @Test
    @DisplayName("检索器把租户、提问人、知识库范围交给知识检索；缺少身份时直接拒绝")
    void retrieverPassesIdentityAndFailsClosed() {
        var seen = new ArrayList<SearchQuery>();
        var retriever = new KnowledgeDocumentRetriever(query -> {
            seen.add(query);
            return List.of(SHELF);
        }, 30);

        var documents = retriever.retrieve(AnswerContext.query("青柚在哪", TENANT, USER, List.of(3L)));

        assertThat(seen).singleElement().satisfies(query -> {
            assertThat(query.tenantId()).isEqualTo(TENANT);
            assertThat(query.userId()).isEqualTo(USER);
            assertThat(query.knowledgeBaseIds()).containsExactly(3L);
            assertThat(query.text()).isEqualTo("青柚在哪");
            assertThat(query.limit()).isEqualTo(30);
        });
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.getText()).isEqualTo("青柚气泡水在 03 号货架。");
            assertThat(Evidence.documentName(document)).isEqualTo("商品陈列.txt");
        });
        assertThatThrownBy(() -> retriever.retrieve(new Query("青柚在哪")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("身份");
    }

    @Test
    @DisplayName("重排按分数从高到低排列，分数写进 Document")
    void rerankSortsByScore() {
        var candidates = List.of(Evidence.of(CASH), Evidence.of(SHELF));
        var ranked = new RerankPostProcessor((question, texts) -> List.of(0.1, 0.9))
                .process(new Query("青柚在哪"), candidates);

        assertThat(ranked).extracting(Evidence::documentName).containsExactly("商品陈列.txt", "收银规范.txt");
        assertThat(ranked).extracting(document -> document.getScore()).containsExactly(0.9, 0.1);
    }

    @Test
    @DisplayName("拼证据：按顺序编号，带来源和章节，最后是问题")
    void augmentsWithNumberedEvidence() {
        var augmented = new NumberedEvidenceAugmenter()
                .augment(new Query("青柚在哪"), List.of(Evidence.of(SHELF), Evidence.of(CASH)));

        assertThat(augmented.text())
                .contains("[1] 来源：商品陈列.txt / 饮料\n青柚气泡水在 03 号货架。")
                .contains("[2] 来源：收银规范.txt\n交班清点备用金。")
                .endsWith("问题：青柚在哪");
    }
}
