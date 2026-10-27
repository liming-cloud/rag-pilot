package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import com.liming.rag.knowledge.api.SearchHit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerServiceTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId USER = new UserId(1);
    private static final SearchHit SHELF = new SearchHit(11, 1, "商品陈列.txt", "饮料", "青柚气泡水在 03 号货架。", 0.03);
    private static final SearchHit CASH = new SearchHit(12, 2, "收银规范.txt", "", "交班清点备用金。", 0.02);

    private final List<String> prompts = new ArrayList<>();

    private AnswerService service(List<SearchHit> hits, List<Double> scores, String reply) {
        return new AnswerService(query -> hits, (query, documents) -> scores, (system, user) -> {
            prompts.add(user);
            return reply;
        }, 30, 8, 0.3, false);
    }

    @Test
    @DisplayName("按重排分数排序证据，低于门槛的不进提示词；回答带上对应出处")
    void answersWithCitations() {
        var answer = service(List.of(CASH, SHELF), List.of(0.1, 0.9), "在 03 号货架[1]。").ask(TENANT, USER, List.of(1L), "青柚在哪");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.topRelevance()).isEqualTo(0.9);
        assertThat(answer.text()).isEqualTo("在 03 号货架[1]。");
        assertThat(answer.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.documentName()).isEqualTo("商品陈列.txt"));
        assertThat(prompts.getFirst()).contains("[1] 来源：商品陈列.txt / 饮料").doesNotContain("收银规范");
    }

    @Test
    @DisplayName("四种拒答：无结果、相关度低、模型拒答、没有有效引用；后两种之前不调用模型")
    void refuses() {
        assertThat(service(List.of(), List.of(), "x").ask(TENANT, USER, List.of(1L), "问").refusalReason())
                .isEqualTo(RefusalReason.NO_EVIDENCE);
        assertThat(service(List.of(SHELF), List.of(0.1), "x").ask(TENANT, USER, List.of(1L), "问").refusalReason())
                .isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(prompts).isEmpty();
        assertThat(service(List.of(SHELF), List.of(0.9), "NO_ANSWER").ask(TENANT, USER, List.of(1L), "问").refusalReason())
                .isEqualTo(RefusalReason.MODEL_DECLINED);
        var uncited = service(List.of(SHELF), List.of(0.9), "应该在货架上[5]。").ask(TENANT, USER, List.of(1L), "问");
        assertThat(uncited.refusalReason()).isEqualTo(RefusalReason.UNCITED);
        assertThat(uncited.citations()).isEmpty();
    }

    @Test
    @DisplayName("空问题报参数错误")
    void validatesInput() {
        var service = service(List.of(), List.of(), "x");
        assertThatThrownBy(() -> service.ask(TENANT, USER, List.of(1L), " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("回答带各阶段耗时；没走到的阶段为空")
    void recordsStageTimings() {
        var answered = streaming(List.of("在 03 ", "号货架[1]。"), 0.9).ask(TENANT, USER, List.of(), "青柚在哪", delta -> {
        });
        assertThat(answered.timings().retrievalMillis()).isNotNegative();
        assertThat(answered.timings().rerankMillis()).isNotNull();
        assertThat(answered.timings().firstTokenMillis()).isNotNull();
        assertThat(answered.timings().generationMillis()).isNotNull();

        var low = streaming(List.of("不该出现"), 0.1).ask(TENANT, USER, List.of(), "问", delta -> {
        });
        assertThat(low.timings().rerankMillis()).isNotNull();
        assertThat(low.timings().firstTokenMillis()).isNull();
        assertThat(low.timings().generationMillis()).isNull();

        var none = service(List.of(), List.of(), "x").ask(TENANT, USER, List.of(1L), "问");
        assertThat(none.timings().rerankMillis()).isNull();
    }

    @Test
    @DisplayName("流式：文字按片段流出，最终结果带引用")
    void streamsDeltas() {
        var deltas = new ArrayList<String>();
        var answer = streaming(List.of("在 03 ", "号货架[1]。"), 0.9).ask(TENANT, USER, List.of(), "青柚在哪", deltas::add);

        assertThat(deltas).containsExactly("在 03 ", "号货架[1]。");
        assertThat(answer.refused()).isFalse();
        assertThat(answer.citations()).singleElement().satisfies(c -> assertThat(c.documentName()).isEqualTo("商品陈列.txt"));
    }

    @Test
    @DisplayName("流式：模型回复 NO_ANSWER 时一个字也不流出；相关度低时不调用模型")
    void streamsNothingWhenRefusing() {
        var deltas = new ArrayList<String>();
        var declined = streaming(List.of("NO_", "ANSWER"), 0.9).ask(TENANT, USER, List.of(), "问", deltas::add);
        var low = streaming(List.of("不该出现"), 0.1).ask(TENANT, USER, List.of(), "问", deltas::add);

        assertThat(declined.refusalReason()).isEqualTo(RefusalReason.MODEL_DECLINED);
        assertThat(low.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(deltas).isEmpty();
    }

    private AnswerService streaming(List<String> pieces, double score) {
        ChatGateway chat = new ChatGateway() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                return String.join("", pieces);
            }

            @Override
            public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta) {
                pieces.forEach(onDelta);
                return String.join("", pieces);
            }
        };
        return new AnswerService(query -> List.of(SHELF), (query, documents) -> List.of(score), chat, 30, 8, 0.3,
                false);
    }

    /**
     * 改写场景：只有问题里出现“货架”时重排才给高分；改写器把口语问题改成规范说法。
     * 回答时用员工原来的问题，证据来自改写后的检索。
     */
    private AnswerService rewriting(String rewritten, boolean enabled) {
        ChatGateway chat = new ChatGateway() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                prompts.add(userMessage);
                return rewritten;
            }

            @Override
            public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta) {
                prompts.add(userMessage);
                onDelta.accept("在 03 号货架[1]。");
                return "在 03 号货架[1]。";
            }
        };
        return new AnswerService(query -> List.of(SHELF),
                (query, documents) -> List.of(query.contains("货架") ? 0.9 : 0.05), chat, 30, 8, 0.3, enabled);
    }

    @Test
    @DisplayName("低分时改写问题再检索一次：救回来就用员工原来的问题回答，并记下改写结果和耗时")
    void rewritesOnLowRelevance() {
        var answer = rewriting("青柚气泡水放在哪个货架？", true).ask(TENANT, USER, List.of(), "青柚放哪了");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.rewrittenQuestion()).isEqualTo("青柚气泡水放在哪个货架？");
        assertThat(answer.topRelevance()).isEqualTo(0.9);
        assertThat(answer.timings().rewriteMillis()).isNotNull();
        assertThat(prompts.getFirst()).contains("青柚放哪了");
        assertThat(prompts.getLast()).endsWith("问题：青柚放哪了");
    }

    @Test
    @DisplayName("改写后仍然低分照常拒答；改写器没给出有效改写、或开关关闭时不重试")
    void keepsRefusingWhenRewriteDoesNotHelp() {
        var stillLow = rewriting("青柚在哪里？", true).ask(TENANT, USER, List.of(), "青柚放哪了");
        assertThat(stillLow.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(stillLow.rewrittenQuestion()).isEqualTo("青柚在哪里？");

        for (var invalid : List.of("NO_ANSWER", "青柚放哪了", "  ")) {
            prompts.clear();
            var answer = rewriting(invalid, true).ask(TENANT, USER, List.of(), "青柚放哪了");
            assertThat(answer.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
            assertThat(answer.rewrittenQuestion()).isNull();
        }

        prompts.clear();
        var disabled = rewriting("青柚气泡水放在哪个货架？", false).ask(TENANT, USER, List.of(), "青柚放哪了");
        assertThat(disabled.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(prompts).isEmpty();
    }
}
