package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 知识缺口（#125、D-047）：只统计范围内的拒答和差评，同义问法归一类，已处理的不计入待处理；整类标记已处理；
 * 嵌入模型不可用时退回按原文分组。嵌入模型用固定向量代替：同一个话题的问法给相同的向量。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class KnowledgeGapTests {

    private static final TenantId TENANT = new TenantId(1);

    /** 话题 → 向量方向。没列出的问题各自给一个不同的方向。 */
    private static final Map<String, Integer> TOPICS = Map.of(
            "门店几点打烊", 0, "晚上几点关门", 0, "营业到几点", 0,
            "餐补怎么报销", 1, "餐补报销流程", 1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @MockitoBean
    private EmbeddingClient embeddings;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AnswerLogService log;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TestLogin.asAdmin(http);
        jdbc.update("DELETE FROM answer_record");
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(text -> {
                var vector = new float[EmbeddingClient.DIMENSIONS];
                vector[TOPICS.getOrDefault(text, 10 + Math.floorMod(text.hashCode(), 1000))] = 1;
                return vector;
            }).toList();
        });
    }

    @Test
    @DisplayName("同义问法归一类，无关的不合并；只算范围内的拒答和差评；已处理的留在类里但不计入待处理")
    @SuppressWarnings("unchecked")
    void groupsRefusalsAndDislikes() {
        long first = refuse("门店几点打烊");
        refuse("门店几点打烊");
        refuse("晚上几点关门");
        long resolved = refuse("营业到几点");
        log.markReview(TENANT, new UserId(1), resolved, "RESOLVED", "已补营业时间");
        long disliked = log.record(TENANT, new UserId(2), "餐补怎么报销",
                AskResult.answered("按月报销[1]", List.of(new Citation(1, 1, 1, "员工手册.md", "餐补", "摘录")), 0.8)).id();
        log.rate(TENANT, new UserId(2), disliked, "DOWN", "流程不对");
        refuse("冷柜坏了找谁修");
        // 不该算：答上了且没被点踩、范围外、别的租户。
        log.record(TENANT, new UserId(1), "营业到几点", AskResult.answered("到 22 点[1]", List.of(), 0.9));
        jdbc.update("UPDATE answer_record SET created_at = now() - interval '40 days' WHERE id = ?",
                refuse("很久以前的问题"));
        log.record(new TenantId(2), new UserId(9), "门店几点打烊",
                AskResult.refused(RefusalReason.NO_EVIDENCE, null, "拒答"));

        var report = http.getForObject("/api/answers/gaps?days=30&status=all", Map.class);

        assertThat(report).containsEntry("grouping", "SEMANTIC").containsEntry("records", 6)
                .containsEntry("truncated", false);
        var groups = (List<Map<String, Object>>) report.get("groups");
        assertThat(groups).extracting(group -> group.get("question"))
                .containsExactly("门店几点打烊", "冷柜坏了找谁修", "餐补怎么报销");
        var hours = groups.getFirst();
        assertThat(hours).containsEntry("count", 4).containsEntry("openCount", 3).containsEntry("refusedCount", 4)
                .containsEntry("phrasings", 3);
        assertThat(((Number) hours.get("id")).longValue()).isNotEqualTo(first).isPositive();
        assertThat((List<Map<String, Object>>) hours.get("records")).hasSize(4)
                .anySatisfy(record -> assertThat(record).containsEntry("reviewStatus", "RESOLVED"));
        // 待处理、总次数相同时最近问过的在前。
        assertThat(groups.get(2)).containsEntry("dislikedCount", 1)
                .containsEntry("citedDocuments", List.of("员工手册.md"));
        assertThat((List<Map<String, Object>>) groups.get(2).get("records")).first()
                .satisfies(record -> assertThat(record).containsEntry("comment", "流程不对")
                        .containsKey("askerName"));
    }

    @Test
    @DisplayName("显示当时检索的知识库：点名的库里有权访问的那些，出现多的在前；没有权限的库不记")
    @SuppressWarnings("unchecked")
    void showsSearchedKnowledgeBases() {
        long store = createKnowledgeBase("缺口测试门店库");
        long goods = createKnowledgeBase("缺口测试商品库");
        long foreign = ((Number) jdbc.queryForObject(
                "INSERT INTO knowledge_base (tenant_id, name) VALUES (1, '缺口测试无权库') RETURNING id", Long.class))
                .longValue();
        // 经会话提问：空库里检索不到，记为拒答，连同点名且有权访问的库。
        for (var knowledgeBases : List.of(List.of(store), List.of(store, goods), List.of(store, foreign))) {
            var asked = TestAsk.ask(http, null, knowledgeBases, "门店几点打烊");
            assertThat(asked).containsEntry("refused", true);
        }

        var groups = (List<Map<String, Object>>) http.getForObject("/api/answers/gaps?days=7", Map.class)
                .get("groups");

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group).containsEntry("knowledgeBases", List.of("缺口测试门店库", "缺口测试商品库"));
            assertThat((List<Map<String, Object>>) group.get("records"))
                    .extracting(record -> record.get("knowledgeBases"))
                    .containsExactly(List.of("缺口测试门店库"), List.of("缺口测试门店库", "缺口测试商品库"),
                            List.of("缺口测试门店库"));
        });
    }

    private long createKnowledgeBase(String name) {
        return ((Number) http.postForObject("/api/knowledge-bases", Map.of("name", name), Map.class).get("id"))
                .longValue();
    }

    @Test
    @DisplayName("整类标记已处理后不再出现在待处理里；没有处理权限 403 且不写入")
    @SuppressWarnings("unchecked")
    void resolvesWholeGroup() {
        long a = refuse("门店几点打烊");
        long b = refuse("晚上几点关门");

        var staff = staffToken();
        var denied = http.exchange("/api/answers/gaps/resolve", HttpMethod.POST,
                new HttpEntity<>(Map.of("recordIds", List.of(a, b)), bearer(staff)), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForList("SELECT review_status FROM answer_record WHERE id IN (?, ?)", String.class, a, b))
                .containsOnly("OPEN");

        var done = http.postForObject("/api/answers/gaps/resolve",
                Map.of("recordIds", List.of(a, b), "note", "已补营业时间规定"), Map.class);
        assertThat(done).containsEntry("resolved", 2);
        assertThat(jdbc.queryForList("SELECT resolution_note FROM answer_record WHERE id IN (?, ?)", String.class, a,
                b)).containsOnly("已补营业时间规定");
        assertThat((List<?>) http.getForObject("/api/answers/gaps?days=30", Map.class).get("groups")).isEmpty();
        assertThat(http.postForEntity("/api/answers/gaps/resolve", Map.of("recordIds", List.of(999_999)),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("嵌入模型不可用时退回按原文分组（去掉标点空格后相同的才合并），并标出来")
    @SuppressWarnings("unchecked")
    void fallsBackToText() {
        when(embeddings.embed(anyList())).thenThrow(new ModelCallException("向量化失败"));
        refuse("门店几点打烊？");
        refuse("门店 几点打烊");
        refuse("晚上几点关门");

        var report = http.getForObject("/api/answers/gaps?days=7", Map.class);

        assertThat(report).containsEntry("grouping", "TEXT");
        assertThat((List<Map<String, Object>>) report.get("groups")).extracting(group -> group.get("count"))
                .containsExactly(2, 1);
    }

    @Test
    @DisplayName("天数只能是 1 到 90，状态只能是 open 或 all；没有查看权限 403")
    void validates() {
        assertThat(http.getForEntity("/api/answers/gaps?days=91", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/answers/gaps?status=closed", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/answers/gaps", HttpMethod.GET, new HttpEntity<>(bearer(staffToken())),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private long refuse(String question) {
        return log.record(TENANT, new UserId(1), question,
                AskResult.refused(RefusalReason.LOW_RELEVANCE, 0.01, "拒答")).id();
    }

    private int staffSequence;

    private String staffToken() {
        var username = "gap-staff" + (++staffSequence) + System.nanoTime() % 100000;
        http.postForEntity("/api/users", Map.of("username", username, "displayName", "店员", "password",
                "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http))), Map.class);
        return TestLogin.token(http, username, "staff-password");
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
