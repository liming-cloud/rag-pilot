package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.ChatGateway;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchPlannerTest {

    private final List<String> messages = new ArrayList<>();
    private final List<ChatGateway.Limits> limits = new ArrayList<>();

    private SearchPlanner planner(String reply, long delayMillis) {
        ChatGateway chat = new ChatGateway() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                throw new AssertionError("检索规划应当带输出上限调用");
            }

            @Override
            public String complete(String systemPrompt, String userMessage, Limits callLimits) {
                messages.add(userMessage);
                limits.add(callLimits);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (reply == null) {
                    throw new IllegalStateException("模型不可用");
                }
                return reply;
            }
        };
        return new SearchPlanner(chat, Duration.ofMillis(300));
    }

    @Test
    @DisplayName("模型给出补全后的检索词；带上历史和当前问题，限制输出长度")
    void plansStandaloneQueries() {
        var plan = planner("检索：冷冻柜温度要求是多少", 0).plan("员工：饮料冷柜温度要多少？\n助手：2–6℃。", "那冷冻柜呢？");

        assertThat(plan.outcome()).isEqualTo(SearchPlanner.Outcome.PLANNED);
        assertThat(plan.queries()).containsExactly("冷冻柜温度要求是多少");
        assertThat(plan.smallTalk()).isFalse();
        assertThat(messages.getFirst()).contains("饮料冷柜温度要多少").contains("当前问题：那冷冻柜呢？");
        assertThat(limits.getFirst().maxTokens()).isEqualTo(SearchPlanner.MAX_TOKENS);
    }

    @Test
    @DisplayName("最多两个检索词，去掉引号、空行和重复")
    void keepsAtMostTwoQueries() {
        var plan = planner("检索：“冷柜温度”\n\n检索: 冷冻柜温度\n检索：冷柜温度\n检索：第三个", 0).plan("历史", "比较一下冷柜和冷冻柜");

        assertThat(plan.queries()).containsExactly("冷柜温度", "冷冻柜温度");
    }

    @Test
    @DisplayName("寒暄时不检索")
    void detectsSmallTalk() {
        var plan = planner("不检索", 0).plan("历史", "谢谢");

        assertThat(plan.smallTalk()).isTrue();
        assertThat(plan.queries()).isEmpty();
    }

    @Test
    @DisplayName("超时、出错、格式不对时用原问题检索")
    void fallsBackToQuestion() {
        var timedOut = planner("检索：冷冻柜温度", 1000).plan("历史", "那冷冻柜呢？");
        assertThat(timedOut.outcome()).isEqualTo(SearchPlanner.Outcome.TIMED_OUT);
        assertThat(timedOut.queries()).containsExactly("那冷冻柜呢？");

        var failed = planner(null, 0).plan("历史", "那冷冻柜呢？");
        assertThat(failed.outcome()).isEqualTo(SearchPlanner.Outcome.FAILED);
        assertThat(failed.queries()).containsExactly("那冷冻柜呢？");

        var garbled = planner("冷冻柜要零下十八度。", 0).plan("历史", "那冷冻柜呢？");
        assertThat(garbled.outcome()).isEqualTo(SearchPlanner.Outcome.FAILED);
        assertThat(garbled.queries()).containsExactly("那冷冻柜呢？");
        assertThat(garbled.smallTalk()).isFalse();
    }

    @Test
    @DisplayName("提示词写明：表示知道了、致谢的话即使接在业务问题后面也不检索")
    void promptCoversAcknowledgements() {
        assertThat(SearchPlanner.SYSTEM).contains("好的，谢谢", "明白了", "即使前面一直在问业务问题也一样");
    }
}
