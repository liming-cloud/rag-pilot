package com.liming.rag.answering.application.ask;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.TestExecutor;
import com.liming.rag.answering.application.TestSettings;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import com.liming.rag.answering.application.ask.AskResult.Rewrite.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowRelevanceRecoveryTest {

    private static final SearchRequest ORIGINAL = new SearchRequest("饮料放哪", new TenantId(1), new UserId(7), List.of());
    private final List<String> statuses = new ArrayList<>();

    @Test
    @DisplayName("改写后最高分更高才采用新结果，并记下改写后的问题")
    void adoptsBetterResult() {
        var result = recovery("青柚气泡水在哪个货架", 0.9).recover(ORIGINAL, ranked(0.1), new StageClock(), statuses::add);

        assertThat(result.ranked().getFirst().score()).isEqualTo(0.9);
        assertThat(result.rewrite().outcome()).isEqualTo(Outcome.REWRITTEN);
        assertThat(result.rewrite().question()).isEqualTo("青柚气泡水在哪个货架");
        assertThat(statuses).containsExactly(LowRelevanceRecovery.REWRITING_STATUS);
    }

    @Test
    @DisplayName("改写后分数没有更高时保留原结果")
    void keepsOriginalWhenNotBetter() {
        var original = ranked(0.1);

        var result = recovery("青柚气泡水在哪个货架", 0.05).recover(ORIGINAL, original, new StageClock(), statuses::add);

        assertThat(result.ranked()).isSameAs(original);
        assertThat(result.rewrite().outcome()).isEqualTo(Outcome.REWRITTEN);
    }

    @Test
    @DisplayName("原最高分低于下限时直接跳过：不调用模型，不发状态提示")
    void skipsBelowFloor() {
        var result = recovery("不会用到", 0.9).recover(ORIGINAL, ranked(0.01), new StageClock(), statuses::add);

        assertThat(result.rewrite().outcome()).isEqualTo(Outcome.SKIPPED_BELOW_FLOOR);
        assertThat(statuses).isEmpty();
    }

    /** 改写模型固定回 {@code rewritten}；改写后的问题重新检索、重排后得到 {@code rewrittenScore}。 */
    private static LowRelevanceRecovery recovery(String rewritten, double rewrittenScore) {
        var rewriter = new LowRelevanceRewriter((instructions, question) -> rewritten, Duration.ofSeconds(1),
                TestSettings.RETAIL_PACK, TestExecutor.VIRTUAL);
        return new LowRelevanceRecovery(new EvidenceRetriever(query -> List.of(HIT), 30),
                new EvidenceReranker((query, texts) -> List.of(rewrittenScore)), rewriter, 0.03);
    }

    private static final SearchHit HIT = new SearchHit(1, 1, "商品陈列.txt", "", "青柚气泡水在 03 号货架。", 0);

    private static List<Evidence> ranked(double score) {
        return List.of(Evidence.of(HIT).withScore(score));
    }
}
