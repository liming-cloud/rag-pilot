package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.RefusalReason;
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

/** 运营周报（#125、D-047）：按自然周统计并与上一周对比，按统计时区切周；导出问答明细 CSV。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class UsageReportTests {

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
    @DisplayName("按周：按统计时区切周（周一 00:30 北京时间算这一周），数字与每日之和一致，并给出上一周的汇总")
    @SuppressWarnings("unchecked")
    void summarizesWeek() {
        TestLogin.asAdmin(http);
        var zone = ZoneId.of("Asia/Shanghai");
        var lastMonday = LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY).minusWeeks(1);
        // 上周一 00:30（北京时间）= 上周日 16:30（UTC）：按北京时间属于上周；上周日 23:30 也属于上周。
        at(lastMonday.atTime(0, 30).atZone(zone), "周一凌晨的问题", true);
        at(lastMonday.plusDays(6).atTime(23, 30).atZone(zone), "周日深夜的问题", false);
        // 上上周日 23:59 属于上上周，作为对比。
        at(lastMonday.minusDays(1).atTime(23, 59).atZone(zone), "上上周的问题", true);
        // 本周一 00:00 不属于上周。
        at(lastMonday.plusWeeks(1).atStartOfDay(zone), "本周的问题", false);

        var stats = http.getForObject("/api/answers/stats?week=" + lastMonday, Map.class);

        assertThat(stats).containsEntry("from", lastMonday.toString())
                .containsEntry("to", lastMonday.plusDays(6).toString());
        var totals = (Map<String, Object>) stats.get("totals");
        var daily = (List<Map<String, Object>>) stats.get("daily");
        assertThat(daily).hasSize(7);
        assertThat(totals).containsEntry("questions", 2).containsEntry("refused", 1);
        assertThat(daily.stream().mapToInt(day -> (Integer) day.get("questions")).sum()).isEqualTo(2);
        assertThat(daily.getFirst()).containsEntry("date", lastMonday.toString()).containsEntry("questions", 1);
        assertThat(daily.getLast()).containsEntry("questions", 1);
        assertThat((Map<String, Object>) stats.get("previous")).containsEntry("questions", 1)
                .containsEntry("refused", 1);
    }

    @Test
    @DisplayName("按周只能选最近 12 周的周一；天数和按周不能同时给")
    void validatesWeek() {
        TestLogin.asAdmin(http);
        var monday = LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.DayOfWeek.MONDAY);
        assertThat(http.getForEntity("/api/answers/stats?week=" + monday, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/answers/stats?week=" + monday.minusWeeks(11), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        for (var bad : List.of("week=" + monday.plusDays(1), "week=" + monday.minusWeeks(12),
                "week=" + monday.plusWeeks(1), "week=2026/11/02", "days=7&week=" + monday)) {
            assertThat(http.getForEntity("/api/answers/stats?" + bad, String.class).getStatusCode()).as(bad)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("导出明细：UTF-8 带 BOM 的 CSV，每条问答一行，引号逗号转义、公式开头加单引号；没有导出权限 403")
    void exportsCsv() {
        TestLogin.asAdmin(http);
        long id = log.record(TENANT, new UserId(1), "=SUM(A1) 怎么算, \"折扣\"",
                AskResult.refused(RefusalReason.NO_EVIDENCE, null, "拒答")).id();

        var response = http.getForEntity("/api/answers/stats/export?days=7", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).startsWith("运营明细_")
                .endsWith(".csv");
        var csv = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF时间,提问人,问题,结果,评价,留言,引用文档,处理状态\r\n");
        assertThat(csv).contains(",\"'=SUM(A1) 怎么算, \"\"折扣\"\"\",答不上来,,,,待处理\r\n");
        assertThat(id).isPositive();

        http.postForEntity("/api/users", Map.of("username", "export-viewer", "displayName", "只看统计",
                "password", "viewer-password", "roleIds", List.of(statsViewerRole())), Map.class);
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestLogin.token(http, "export-viewer", "viewer-password"));
        assertThat(http.exchange("/api/answers/stats?days=7", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/answers/stats/export?days=7", HttpMethod.GET, new HttpEntity<>(headers),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private long statsViewerRole() {
        var created = http.postForEntity("/api/roles", Map.of("name", "只看统计",
                "permissions", List.of("stats:view")), Map.class);
        return ((Number) created.getBody().get("id")).longValue();
    }

    /** 在指定时间记一条问答。 */
    private void at(java.time.ZonedDateTime time, String question, boolean refused) {
        var answer = refused ? AskResult.refused(RefusalReason.NO_EVIDENCE, null, "拒答")
                : AskResult.answered("答案", List.of(), 0.9);
        long id = log.record(TENANT, new UserId(1), question, answer).id();
        jdbc.update("UPDATE answer_record SET created_at = ? WHERE id = ?", time.toOffsetDateTime(), id);
    }
}
