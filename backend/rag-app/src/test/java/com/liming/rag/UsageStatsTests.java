package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.api.Citation;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 试点使用统计：按北京时间的日期汇总回答记录。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class UsageStatsTests {

    private static final TenantId TENANT = new TenantId(1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AnswerLogService log;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("汇总、每日趋势、拒答原因、答不上来的问题、引用最多的文档；超出范围的记录不算")
    @SuppressWarnings("unchecked")
    void summarizes() {
        TestLogin.asAdmin(http);
        var guide = new Citation(1, 1, 1, "临期与报损管理.md", "临期", "摘录");
        var shelf = new Citation(2, 2, 2, "陈列规范.md", "陈列", "摘录");
        long liked = log.record(TENANT, new UserId(1), "临期商品打几折", Answer.answered("七折[1]", List.of(guide), 0.9)).id();
        long disliked = log.record(TENANT, new UserId(2), "临期怎么贴标签",
                Answer.answered("贴黄标[1][2]", List.of(guide, shelf), 0.8)).id();
        log.record(TENANT, new UserId(2), "门店几点打烊", Answer.refused(Answer.RefusalReason.LOW_RELEVANCE, 0.01));
        log.record(TENANT, new UserId(3), "门店几点打烊", Answer.refused(Answer.RefusalReason.LOW_RELEVANCE, 0.02));
        long resolved = log.record(TENANT, new UserId(3), "餐补怎么报",
                Answer.refused(Answer.RefusalReason.NO_EVIDENCE, null)).id();
        log.rate(TENANT, new UserId(1), liked, "UP", null);
        log.rate(TENANT, new UserId(2), disliked, "DOWN", "不对");
        log.markReview(TENANT, new UserId(1), resolved, "RESOLVED", "已补文档");
        // 范围外的旧记录和别的租户的记录都不算。
        long old = log.record(TENANT, new UserId(1), "很久以前的问题", Answer.refused(Answer.RefusalReason.NO_EVIDENCE, null)).id();
        jdbc.update("UPDATE answer_record SET created_at = now() - interval '40 days' WHERE id = ?", old);
        log.record(new TenantId(2), new UserId(9), "别的租户", Answer.refused(Answer.RefusalReason.NO_EVIDENCE, null));

        var response = http.getForEntity("/api/answers/stats?days=30", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var stats = response.getBody();
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThat(stats).containsEntry("from", today.minusDays(29).toString()).containsEntry("to", today.toString());
        assertThat((Map<String, Object>) stats.get("totals")).containsEntry("questions", 5).containsEntry("askers", 3)
                .containsEntry("refused", 3).containsEntry("rated", 2).containsEntry("liked", 1)
                .containsEntry("disliked", 1).containsEntry("openReviews", 3);
        var daily = (List<Map<String, Object>>) stats.get("daily");
        assertThat(daily).hasSize(30);
        assertThat(daily.getFirst()).containsEntry("questions", 0);
        assertThat(daily.getLast()).containsEntry("date", today.toString()).containsEntry("questions", 5)
                .containsEntry("refused", 3).containsEntry("disliked", 1);
        assertThat((List<Map<String, Object>>) stats.get("refusalReasons")).containsExactly(
                Map.of("reason", "LOW_RELEVANCE", "count", 2), Map.of("reason", "NO_EVIDENCE", "count", 1));
        assertThat((List<Map<String, Object>>) stats.get("topRefusedQuestions")).first()
                .isEqualTo(Map.of("question", "门店几点打烊", "count", 2));
        assertThat((List<Map<String, Object>>) stats.get("topCitedDocuments")).containsExactly(
                Map.of("documentName", "临期与报损管理.md", "count", 2), Map.of("documentName", "陈列规范.md", "count", 1));
    }

    @Test
    @DisplayName("天数只能是 1 到 90；店员不能看")
    void validates() {
        TestLogin.asAdmin(http);
        assertThat(http.getForEntity("/api/answers/stats?days=0", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/answers/stats?days=91", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        http.postForEntity("/api/users", Map.of("username", "stats-staff", "displayName", "店员",
                "password", "staff-password", "role", "STAFF"), Map.class);
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestLogin.token(http, "stats-staff", "staff-password"));
        assertThat(http.exchange("/api/answers/stats?days=7", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
