package com.liming.rag.answering.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.TextGeneration;
import com.liming.rag.answering.application.TestExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchPlannerTest {

    private final List<String> messages = new ArrayList<>();
    private final List<TextGeneration.Limits> limits = new ArrayList<>();

    /** {@code reply} 为 null 时模拟模型不可用，为 {@link #GARBLED} 时模拟输出不是约定的 JSON。 */
    private SearchPlanner planner(SearchPlanner.Reply reply, long delayMillis) {
        TextGeneration chat = new TextGeneration() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                throw new AssertionError("检索规划应当用结构化输出");
            }

            @Override
            public <T> T structured(String systemPrompt, String userMessage, Class<T> type, Limits callLimits) {
                messages.add(userMessage);
                limits.add(callLimits);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (reply == null) {
                    throw new ModelCallException("模型不可用");
                }
                if (reply == GARBLED) {
                    throw new ModelCallException("模型没有按约定的格式回复");
                }
                return type.cast(reply);
            }
        };
        return new SearchPlanner(chat, Duration.ofMillis(300), TestExecutor.VIRTUAL);
    }

    private static final SearchPlanner.Reply GARBLED = new SearchPlanner.Reply(false, List.of("格式不对"));

    @Test
    @DisplayName("模型用结构化输出给出补全后的检索词；带上历史和当前问题，限制输出长度（#145）")
    void plansStandaloneQueries() {
        var plan = planner(new SearchPlanner.Reply(false, List.of("冷冻柜温度要求是多少")), 0)
                .plan("员工：饮料冷柜温度要多少？\n助手：2–6℃。", "那冷冻柜呢？");

        assertThat(plan.outcome()).isEqualTo(SearchPlanner.Outcome.PLANNED);
        assertThat(plan.queries()).containsExactly("冷冻柜温度要求是多少");
        assertThat(plan.smallTalk()).isFalse();
        assertThat(messages.getFirst()).contains("饮料冷柜温度要多少").contains("当前问题：那冷冻柜呢？");
        assertThat(limits.getFirst().maxTokens()).isEqualTo(SearchPlanner.MAX_TOKENS);
    }

    @Test
    @DisplayName("最多两个检索词，去掉引号、空串和重复")
    void keepsAtMostTwoQueries() {
        var plan = planner(new SearchPlanner.Reply(false, List.of("“冷柜温度”", " ", "冷冻柜温度", "冷柜温度", "第三个")), 0)
                .plan("历史", "比较一下冷柜和冷冻柜");

        assertThat(plan.queries()).containsExactly("冷柜温度", "冷冻柜温度");
    }

    @Test
    @DisplayName("寒暄时不检索")
    void detectsSmallTalk() {
        var plan = planner(new SearchPlanner.Reply(true, List.of()), 0).plan("历史", "谢谢");

        assertThat(plan.smallTalk()).isTrue();
        assertThat(plan.queries()).isEmpty();
    }

    @Test
    @DisplayName("超时、出错、格式不对、没给出可用的检索词时用原问题检索")
    void fallsBackToQuestion() {
        var timedOut = planner(new SearchPlanner.Reply(false, List.of("冷冻柜温度")), 1000).plan("历史", "那冷冻柜呢？");
        assertThat(timedOut.outcome()).isEqualTo(SearchPlanner.Outcome.TIMED_OUT);
        assertThat(timedOut.queries()).containsExactly("那冷冻柜呢？");

        var failed = planner(null, 0).plan("历史", "那冷冻柜呢？");
        assertThat(failed.outcome()).isEqualTo(SearchPlanner.Outcome.FAILED);
        assertThat(failed.queries()).containsExactly("那冷冻柜呢？");

        var garbled = planner(GARBLED, 0).plan("历史", "那冷冻柜呢？");
        assertThat(garbled.outcome()).isEqualTo(SearchPlanner.Outcome.FAILED);
        assertThat(garbled.queries()).containsExactly("那冷冻柜呢？");
        assertThat(garbled.smallTalk()).isFalse();

        var empty = planner(new SearchPlanner.Reply(false, List.of()), 0).plan("历史", "那冷冻柜呢？");
        assertThat(empty.outcome()).isEqualTo(SearchPlanner.Outcome.FAILED);
        assertThat(empty.queries()).containsExactly("那冷冻柜呢？");
    }

    @Test
    @DisplayName("提示词写明：表示知道了、致谢的话即使接在业务问题后面也不检索")
    void promptCoversAcknowledgements() {
        assertThat(SearchPlanner.SYSTEM).contains("好的，谢谢", "明白了", "即使前面一直在问业务问题也一样");
    }
}
