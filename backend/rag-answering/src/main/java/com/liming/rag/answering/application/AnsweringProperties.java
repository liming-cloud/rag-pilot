package com.liming.rag.answering.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 问答配置（{@code rag.answering}）。取值在构造时校验，非法时应用启动失败，不带着错误的门槛运行。
 *
 * @param candidates            检索候选数
 * @param evidenceLimit         送进提示词的证据数，不多于候选数
 * @param minRelevance          重排相关度门槛，低于它的候选不作为证据（D-032）
 * @param rewriteOnLowRelevance 都低于门槛时是否改写问题再检索一次（D-022）
 * @param rewriteMinRelevance   改写前最高分低于这个下限时不改写，直接拒答（D-023）
 * @param rewriteTimeout        改写超过这个时限就放弃
 * @param conversation          员工端会话（D-036）
 */
@ConfigurationProperties("rag.answering")
public record AnsweringProperties(
        @DefaultValue("30") int candidates,
        @DefaultValue("8") int evidenceLimit,
        @DefaultValue("0.2") double minRelevance,
        @DefaultValue("true") boolean rewriteOnLowRelevance,
        @DefaultValue("0.03") double rewriteMinRelevance,
        @DefaultValue("3s") Duration rewriteTimeout,
        @DefaultValue Conversation conversation) {

    /**
     * 员工端会话的配置（{@code rag.answering.conversation}）。
     *
     * @param retention       员工端会话从最后一次提问起保留多久，到期清理；回答记录不受影响
     * @param planningTurns   检索规划看最近几轮
     * @param generationTurns 生成回答时带最近几轮，不多于 planningTurns
     * @param historyMaxChars 带进提示词的历史最多多少字（约 2000 token），超出时丢最早的轮次
     * @param planningTimeout 检索规划超过这个时限就放弃，用原问题检索
     */
    public record Conversation(
            @DefaultValue("30d") Duration retention,
            @DefaultValue("6") int planningTurns,
            @DefaultValue("3") int generationTurns,
            @DefaultValue("3000") int historyMaxChars,
            @DefaultValue("3s") Duration planningTimeout) {

        public Conversation {
            require(retention != null && retention.compareTo(Duration.ofDays(1)) >= 0,
                    "rag.answering.conversation.retention 至少 1 天");
            require(planningTurns >= 1 && planningTurns <= 20, "rag.answering.conversation.planning-turns 必须在 1 到 20 之间");
            require(generationTurns >= 0 && generationTurns <= planningTurns,
                    "rag.answering.conversation.generation-turns 必须在 0 到 planning-turns 之间");
            require(historyMaxChars >= 500 && historyMaxChars <= 20000,
                    "rag.answering.conversation.history-max-chars 必须在 500 到 20000 之间");
            require(planningTimeout != null && planningTimeout.isPositive(),
                    "rag.answering.conversation.planning-timeout 必须大于 0");
        }

        static Conversation defaults() {
            return new Conversation(Duration.ofDays(30), 6, 3, 3000, Duration.ofSeconds(3));
        }
    }

    /** 不关心会话配置的地方（如单元测试）用默认的会话配置。 */
    public AnsweringProperties(int candidates, int evidenceLimit, double minRelevance, boolean rewriteOnLowRelevance,
            double rewriteMinRelevance, Duration rewriteTimeout) {
        this(candidates, evidenceLimit, minRelevance, rewriteOnLowRelevance, rewriteMinRelevance, rewriteTimeout,
                Conversation.defaults());
    }

    @ConstructorBinding
    public AnsweringProperties {
        require(candidates >= 1 && candidates <= 200, "rag.answering.candidates 必须在 1 到 200 之间");
        require(evidenceLimit >= 1 && evidenceLimit <= candidates,
                "rag.answering.evidence-limit 必须在 1 到 candidates 之间");
        require(minRelevance >= 0 && minRelevance <= 1, "rag.answering.min-relevance 必须在 0 到 1 之间");
        // 不要求改写下限 ≤ 门槛：评测校准门槛时会把 min-relevance 调到 0 附近（eval 工作流的输入）。
        require(rewriteMinRelevance >= 0 && rewriteMinRelevance <= 1,
                "rag.answering.rewrite-min-relevance 必须在 0 到 1 之间");
        require(rewriteTimeout != null && rewriteTimeout.isPositive(), "rag.answering.rewrite-timeout 必须大于 0");
        conversation = conversation == null ? Conversation.defaults() : conversation;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
