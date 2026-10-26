package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.common.TenantId;
import com.liming.rag.knowledge.api.SearchHit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerServiceTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final SearchHit SHELF = new SearchHit(11, 1, "商品陈列.txt", "饮料", "青柚气泡水在 03 号货架。", 0.03);
    private static final SearchHit CASH = new SearchHit(12, 2, "收银规范.txt", "", "交班清点备用金。", 0.02);

    private final List<String> prompts = new ArrayList<>();

    private AnswerService service(List<SearchHit> hits, List<Double> scores, String reply) {
        return new AnswerService(query -> hits, (query, documents) -> scores, (system, user) -> {
            prompts.add(user);
            return reply;
        }, 30, 8, 0.3);
    }

    @Test
    @DisplayName("按重排分数排序证据，低于门槛的不进提示词；回答带上对应出处")
    void answersWithCitations() {
        var answer = service(List.of(CASH, SHELF), List.of(0.1, 0.9), "在 03 号货架[1]。").ask(TENANT, List.of(1L), "青柚在哪");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.text()).isEqualTo("在 03 号货架[1]。");
        assertThat(answer.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.documentName()).isEqualTo("商品陈列.txt"));
        assertThat(prompts.getFirst()).contains("[1] 来源：商品陈列.txt / 饮料").doesNotContain("收银规范");
    }

    @Test
    @DisplayName("四种拒答：无结果、相关度低、模型拒答、没有有效引用；后两种之前不调用模型")
    void refuses() {
        assertThat(service(List.of(), List.of(), "x").ask(TENANT, List.of(1L), "问").refusalReason())
                .isEqualTo(RefusalReason.NO_EVIDENCE);
        assertThat(service(List.of(SHELF), List.of(0.1), "x").ask(TENANT, List.of(1L), "问").refusalReason())
                .isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(prompts).isEmpty();
        assertThat(service(List.of(SHELF), List.of(0.9), "NO_ANSWER").ask(TENANT, List.of(1L), "问").refusalReason())
                .isEqualTo(RefusalReason.MODEL_DECLINED);
        var uncited = service(List.of(SHELF), List.of(0.9), "应该在货架上[5]。").ask(TENANT, List.of(1L), "问");
        assertThat(uncited.refusalReason()).isEqualTo(RefusalReason.UNCITED);
        assertThat(uncited.citations()).isEmpty();
    }

    @Test
    @DisplayName("空问题或未选知识库时报参数错误")
    void validatesInput() {
        var service = service(List.of(), List.of(), "x");
        assertThatThrownBy(() -> service.ask(TENANT, List.of(1L), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask(TENANT, List.of(), "问")).isInstanceOf(IllegalArgumentException.class);
    }
}
