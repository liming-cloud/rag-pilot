package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 知识缺口接口在 1,000 条记录时的耗时（#145 C6），默认不跑（标签 benchmark），手动运行：
 * {@code ./mvnw verify -pl backend/boot/rag-app -am -Dtest.excludedGroups=eval -Dtest=KnowledgeGapBenchmark
 * -Dsurefire.failIfNoSpecifiedTests=false}。200 种问题各问 5 次、都被拒答，等后台存好问题向量后连续打开缺口页，
 * 结果打印在输出里（p50、p95，毫秒）。只用来比较改动前后，数字随机器变化，不作断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-gap-benchmark-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
@Tag("benchmark")
class KnowledgeGapBenchmark {

    private static final int RECORDS = 1000;
    private static final int TIMES = 50;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnswerLogService answers;

    @Test
    @SuppressWarnings("unchecked")
    void measure() throws InterruptedException {
        TestLogin.asAdmin(http);
        var topics = List.of("临期商品", "冷柜温度", "交班备用金", "会员积分", "退款授权", "夜班补贴", "补货阈值", "灭火器检查");
        for (int i = 0; i < RECORDS; i++) {
            var question = "第 " + (i % 200) + " 号门店的" + topics.get(i % 200 % topics.size()) + "怎么处理";
            answers.record(new TenantId(1), new UserId(1), question,
                    AskResult.refused(RefusalReason.LOW_RELEVANCE, 0.0, "拒答"));
        }
        awaitEmbeddings();

        var report = http.getForObject("/api/answers/gaps?days=30", Map.class);
        assertThat((List<?>) report.get("groups")).isNotEmpty();
        for (int i = 0; i < 5; i++) {
            http.getForObject("/api/answers/gaps?days=30", Map.class);
        }
        var nanos = new long[TIMES];
        for (int i = 0; i < TIMES; i++) {
            long started = System.nanoTime();
            http.getForObject("/api/answers/gaps?days=30", Map.class);
            nanos[i] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        System.out.printf("benchmark case=GET /api/answers/gaps records=%d groups=%d n=%d p50=%.1fms p95=%.1fms%n",
                RECORDS, ((List<?>) report.get("groups")).size(), TIMES, nanos[TIMES / 2] / 1e6,
                nanos[(int) (TIMES * 0.95)] / 1e6);
    }

    /** 问题向量在记下回答、事务提交后由后台算好写回，等它们都写完（最多 60 秒）。 */
    private void awaitEmbeddings() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (jdbc.queryForObject("SELECT count(*) FROM answer_record WHERE question_embedding IS NULL",
                Long.class) > 0) {
            assertThat(System.nanoTime()).as("后台应在 60 秒内存好问题向量").isLessThan(deadline);
            Thread.sleep(200);
        }
    }
}
