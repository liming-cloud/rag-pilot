package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.shared.ErrorKind;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Duration;
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
        }, new AnsweringProperties(30, 8, 0.3, false, 0.03, Duration.ofSeconds(3)));
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
        assertThatThrownBy(() -> service.ask(TENANT, USER, List.of(1L), " ")).isInstanceOf(AnsweringException.class)
                .extracting("kind").isEqualTo(ErrorKind.INVALID);
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

    @Test
    @DisplayName("答了一部分又在末尾或中间输出 NO_ANSWER：去掉这个标记，流式输出也不出现；只剩标记时按模型拒答")
    void stripsTrailingNoAnswerMarker() {
        var deltas = new ArrayList<String>();
        var answer = streaming(List.of("在 03 号货架[1]。估值资料里没有提到。", "  NO_", "ANSWER"), 0.9)
                .ask(TENANT, USER, List.of(), "青柚在哪，卖多少钱", deltas::add);

        assertThat(answer.refused()).isFalse();
        assertThat(answer.text()).isEqualTo("在 03 号货架[1]。估值资料里没有提到。");
        assertThat(String.join("", deltas)).doesNotContain("NO_").doesNotContain("ANSWER")
                .startsWith("在 03 号货架[1]。估值资料里没有提到。");

        deltas.clear();
        var middle = streaming(List.of("在 03 号货架[1]。NO_ANSWER（价格未提及）"), 0.9)
                .ask(TENANT, USER, List.of(), "青柚在哪，卖多少钱", deltas::add);
        assertThat(middle.text()).isEqualTo("在 03 号货架[1]。（价格未提及）");
        assertThat(String.join("", deltas)).isEqualTo("在 03 号货架[1]。（价格未提及）");

        // 没被标记截断的普通文字照常全部流出，结尾刚好像标记开头的片段也不丢。
        deltas.clear();
        streaming(List.of("在 03 号货架[1]。N"), 0.9).ask(TENANT, USER, List.of(), "问", deltas::add);
        assertThat(String.join("", deltas)).isEqualTo("在 03 号货架[1]。N");

        assertThat(service(List.of(SHELF), List.of(0.9), "  NO_ANSWER\n").ask(TENANT, USER, List.of(1L), "问")
                .refusalReason()).isEqualTo(RefusalReason.MODEL_DECLINED);
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
        return new AnswerService(query -> List.of(SHELF), (query, documents) -> List.of(score), chat,
                new AnsweringProperties(30, 8, 0.3, false, 0.03, Duration.ofSeconds(3)));
    }

    /**
     * 改写场景：只有问题里出现“货架”时重排才给高分；改写器把口语问题改成规范说法。
     * 回答时用员工原来的问题，证据来自改写后的检索。
     */
    private AnswerService rewriting(String rewritten, boolean enabled) {
        return rewriting(rewritten, enabled, 0.05, 0);
    }

    /** 改写时传给模型的输出上限，用来核对改写调用确实单独限了长度。 */
    private final List<ChatGateway.Limits> rewriteLimits = new ArrayList<>();

    /**
     * 改写场景：只有问题里出现“货架”时重排才给高分，原问题得 {@code originalScore}；改写器把口语问题改成规范说法，
     * 耗时 {@code delayMillis}。回答时用员工原来的问题，证据来自改写后的检索。
     */
    private AnswerService rewriting(String rewritten, boolean enabled, double originalScore, long delayMillis) {
        ChatGateway chat = new ChatGateway() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                throw new AssertionError("改写应当带输出上限调用");
            }

            @Override
            public String complete(String systemPrompt, String userMessage, Limits limits) {
                prompts.add(userMessage);
                rewriteLimits.add(limits);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
                (query, documents) -> List.of(query.contains("货架") ? 0.9 : originalScore), chat,
                new AnsweringProperties(30, 8, 0.3, enabled, 0.03, Duration.ofMillis(300)));
    }

    @Test
    @DisplayName("改写单独限制输出长度、遇换行停止；分段记下模型改写、重新检索、重新重排的耗时；开始改写时发状态提示")
    void limitsRewriteAndTracesStages() {
        var statuses = new ArrayList<String>();
        var answer = rewriting("青柚气泡水放在哪个货架？", true).ask(TENANT, USER, List.of(), "青柚放哪了", delta -> {
        }, statuses::add);

        assertThat(rewriteLimits).singleElement().satisfies(limits -> {
            assertThat(limits.maxTokens()).isEqualTo(64);
            assertThat(limits.stop()).containsExactly("\n");
        });
        assertThat(answer.rewrite().outcome()).isEqualTo(Answer.Rewrite.Outcome.REWRITTEN);
        assertThat(answer.rewrite().outputChars()).isEqualTo("青柚气泡水放在哪个货架？".length());
        assertThat(answer.rewrite().modelMillis()).isNotNull();
        assertThat(answer.rewrite().retrievalMillis()).isNotNull();
        assertThat(answer.rewrite().rerankMillis()).isNotNull();
        assertThat(statuses).containsExactly("换个说法再找找…");
    }

    @Test
    @DisplayName("改写前最高分低于下限时直接拒答，不调用模型、不发状态提示")
    void skipsRewriteBelowFloor() {
        var statuses = new ArrayList<String>();
        var answer = rewriting("青柚气泡水放在哪个货架？", true, 0.01, 0).ask(TENANT, USER, List.of(), "青柚放哪了",
                delta -> {
                }, statuses::add);

        assertThat(answer.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(answer.rewrite().outcome()).isEqualTo(Answer.Rewrite.Outcome.SKIPPED_BELOW_FLOOR);
        assertThat(answer.rewrittenQuestion()).isNull();
        assertThat(prompts).isEmpty();
        assertThat(statuses).isEmpty();
    }

    @Test
    @DisplayName("改写超时就放弃，按原方式拒答，不等模型返回")
    void givesUpWhenRewriteTimesOut() {
        long start = System.nanoTime();
        var answer = rewriting("青柚气泡水放在哪个货架？", true, 0.05, 2_000).ask(TENANT, USER, List.of(), "青柚放哪了");

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_500);
        assertThat(answer.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(answer.rewrite().outcome()).isEqualTo(Answer.Rewrite.Outcome.TIMED_OUT);
        assertThat(answer.rewrittenQuestion()).isNull();
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
            assertThat(answer.rewrite().outcome()).isEqualTo(Answer.Rewrite.Outcome.NO_CHANGE);
        }

        prompts.clear();
        var disabled = rewriting("青柚气泡水放在哪个货架？", false).ask(TENANT, USER, List.of(), "青柚放哪了");
        assertThat(disabled.refusalReason()).isEqualTo(RefusalReason.LOW_RELEVANCE);
        assertThat(prompts).isEmpty();
    }

    private static final SearchHit FROZEN = new SearchHit(13, 3, "冷链规范.txt", "冷冻柜", "冷冻柜保持零下十八度以下。", 0.02);

    /** 追问场景：检索词含“冷冻”时检索到冷冻柜的资料，含“冷柜”时检索到货架资料；重排只给和检索词对得上的资料高分。 */
    private AnswerService followUp(List<String> retrieved) {
        ChatGateway chat = new ChatGateway() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                throw new AssertionError("生成应当走流式");
            }

            @Override
            public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta) {
                prompts.add(userMessage);
                onDelta.accept("零下十八度[1]，货架[2]。");
                return "零下十八度[1]，货架[2]。";
            }
        };
        return new AnswerService(query -> {
            retrieved.add(query.text());
            return query.text().contains("冷冻") ? List.of(FROZEN) : List.of(SHELF);
        }, (query, documents) -> documents.stream()
                .map(document -> (query.contains("冷冻") == document.contains("冷冻")) ? 0.9 : 0.05).toList(),
                chat, new AnsweringProperties(30, 8, 0.3, false, 0.03, Duration.ofSeconds(3)));
    }

    @Test
    @DisplayName("追问：每个检索词各检索一次，合并后按对各自检索词的重排分数取高；提示词带历史和员工原来的问题")
    void answersFollowUpWithPlannedQueries() {
        var retrieved = new ArrayList<String>();
        var request = new AnswerService.AskRequest(TENANT, USER, List.of(1L), "那冷冻柜呢？跟冷柜比呢",
                List.of("冷冻柜温度要求", "冷柜货架位置"), "员工：冷柜在哪？\n助手：03 号货架。");

        var answer = followUp(retrieved).ask(request, delta -> {
        }, status -> {
        }, new java.util.concurrent.CompletableFuture<Void>());

        assertThat(retrieved).containsExactly("冷冻柜温度要求", "冷柜货架位置");
        assertThat(answer.refused()).isFalse();
        assertThat(answer.citations()).extracting(citation -> citation.documentName())
                .containsExactly("冷链规范.txt", "商品陈列.txt");
        assertThat(prompts.getFirst()).contains("前面的对话", "员工：冷柜在哪？").endsWith("问题：那冷冻柜呢？跟冷柜比呢");
    }

    @Test
    @DisplayName("首字前按阶段推状态：检索资料、整理答案")
    void announcesStages() {
        var statuses = new ArrayList<String>();

        followUp(new ArrayList<>()).ask(new AnswerService.AskRequest(TENANT, USER, List.of(), "冷冻柜温度",
                List.of("冷冻柜温度"), ""), delta -> {
        }, statuses::add, new java.util.concurrent.CompletableFuture<Void>());

        assertThat(statuses).containsExactly("正在检索资料…", "正在整理答案…");
        assertThat(prompts.getFirst()).doesNotContain("前面的对话");
    }

    @Test
    @DisplayName("生成前已经停止时不调用模型")
    void skipsGenerationWhenStopped() {
        var stop = java.util.concurrent.CompletableFuture.<Void>completedFuture(null);

        followUp(new ArrayList<>()).ask(new AnswerService.AskRequest(TENANT, USER, List.of(), "冷冻柜温度",
                List.of("冷冻柜温度"), ""), delta -> {
        }, status -> {
        }, stop);

        assertThat(prompts).isEmpty();
    }

    @Test
    @DisplayName("送给模型的资料去掉行首的列表序号，免得模型把“2.”写成引用 [2]（#71、#107）；员工看到的引用原文不变")
    void stripsListNumbersFromEvidence() {
        var listed = new SearchHit(14, 4, "收银与交接班.md", "交接班", "1. 交班时双方清点钱箱。\n2. 差额超过 20 元须填写《差异说明单》。", 0.02);
        var answer = new AnswerService(query -> List.of(listed), (query, documents) -> List.of(0.9), (system, user) -> {
            prompts.add(user);
            return "差额超过 20 元须填写说明单[1]。";
        }, new AnsweringProperties(30, 8, 0.3, false, 0.03, Duration.ofSeconds(3))).ask(TENANT, USER, List.of(), "差额多少要写说明");

        assertThat(prompts.getFirst()).contains("交班时双方清点钱箱。\n差额超过 20 元").doesNotContain("2. 差额");
        assertThat(answer.citations().getFirst().excerpt()).startsWith("1. 交班时");
    }
}
