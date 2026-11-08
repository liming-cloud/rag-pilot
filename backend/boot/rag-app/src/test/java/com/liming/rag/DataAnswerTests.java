package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.RetailMcpServer;
import com.liming.rag.ai.ToolJwks;
import com.liming.rag.ai.ToolSigningKeys;
import com.liming.rag.answering.application.ask.ToolChoice;
import com.liming.rag.answering.application.ask.ToolSelection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 数据问答（#182）：员工经会话接口提问，按模型选的工具、按提问人的数据范围查询模拟新零售服务（真实的判定、令牌、MCP 调用、
 * 审计和落库）。选工具的模型换成测试替身，给出确定的工具和参数；回答模型用本地确定性实现。
 * 断言回答里的数据出处、范围外不发请求、范围外的行被丢弃、各种失败都如实说查不到且没有数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.knowledge.storage.root=${java.io.tmpdir}/rag-data-answer-test-files",
        "rag.ai.tools.timeout=1s",
        "rag.data.tools[0].name=get_store_inventory", "rag.data.tools[0].title=库存查询",
        "rag.data.tools[1].name=get_store_sales", "rag.data.tools[1].title=销售查询"})
@AutoConfigureTestRestTemplate
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DataAnswerTests {

    private static final String AUDIENCE = "https://erp.example.com/mcp";
    private static final String SIGNING_KEY = ToolSigningKeys.generateJwk("test-key");
    private static final String INVENTORY = "get_store_inventory";
    private static final AtomicReference<ToolJwks> KEYS = new AtomicReference<>();
    private static final AtomicReference<ToolChoice> CHOICE = new AtomicReference<>();
    private static final AtomicInteger USERS = new AtomicInteger();
    private static RetailMcpServer retail;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    /** 选工具的模型：给出测试设定的工具和参数。 */
    @TestConfiguration
    static class FixedSelection {

        @Bean
        @Primary
        ToolSelection fixedToolSelection() {
            return (system, user) -> Optional.ofNullable(CHOICE.get());
        }
    }

    @BeforeAll
    static void startRetail() throws Exception {
        retail = new RetailMcpServer(AUDIENCE, () -> KEYS.get().jwks());
    }

    @AfterAll
    static void stopRetail() {
        retail.close();
    }

    @DynamicPropertySource
    static void tools(DynamicPropertyRegistry registry) {
        registry.add("rag.ai.tools.signing-key", () -> SIGNING_KEY);
        registry.add("rag.ai.tools.endpoints.retail.url", () -> retail.url());
        registry.add("rag.ai.tools.endpoints.retail.audience", () -> AUDIENCE);
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ToolJwks jwks;

    private String admin;
    private String staff;
    private long staffId;

    @BeforeEach
    void setUp() {
        KEYS.set(jwks);
        retail.served.clear();
        admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        var username = "data-staff" + USERS.incrementAndGet();
        long userId = id(exchange(admin, HttpMethod.POST, "/api/users", Map.of("username", username, "displayName",
                "店员", "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http)))));
        // 知识库里没有资料：回答只能依据数据，便于断言数据出处。
        long knowledgeBase = id(exchange(admin, HttpMethod.POST, "/api/knowledge-bases", Map.of("name", username)));
        assertThat(http.exchange("/api/knowledge-bases/{id}/members/{userId}", HttpMethod.PUT,
                new HttpEntity<>(auth(admin)), Void.class, knowledgeBase, userId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(admin, HttpMethod.PUT, "/api/data-grants?granteeType=USER&granteeId=" + userId,
                Map.of("tools", List.of(INVENTORY), "units", List.of("S001"), "regions", List.of())))
                .containsKey("tools");
        staff = TestLogin.token(http, username, "staff-password");
        staffId = userId;
        CHOICE.set(new ToolChoice(INVENTORY, Map.of("store_id", "S001", "sku", "A1001")));
    }

    @AfterEach
    void reset() {
        retail.fault = RetailMcpServer.Fault.NONE;
    }

    private Map<String, Object> ask() {
        return TestAsk.ask(http, staff, null, "S001 的鲜牛奶还剩多少");
    }

    @SuppressWarnings("unchecked") // 回答的 JSON 结构由 AskResult 约定。
    private static List<Map<String, Object>> dataCitations(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("dataCitations");
    }

    private List<String> toolAudits() {
        return jdbc.queryForList("select detail from audit_log where action = 'DATA_TOOL_CALLED' and target_id = ? "
                + "and actor_id = ? order by id", String.class, INVENTORY, staffId);
    }

    @Test
    @DisplayName("范围内：回答依据查到的数据，带来源、条件、数据截至和查询时间；历史回答里同样带着；审计记了令牌编号、没记数据")
    void answersWithDataCitation(CapturedOutput output) {
        var answer = ask();

        assertThat(answer).containsEntry("refused", false);
        assertThat((String) answer.get("text")).contains("quantity=24").contains("[1]");
        assertThat(dataCitations(answer)).singleElement().satisfies(citation -> {
            assertThat(citation).containsEntry("index", 1).containsEntry("tool", INVENTORY)
                    .containsEntry("system", "ERP").containsEntry("dataset", "store_inventory")
                    .containsEntry("conditions", "store_id=S001，sku=A1001").containsEntry("truncated", false);
            assertThat(citation.get("asOf")).isNotNull();
            assertThat(citation.get("queriedAt")).isNotNull();
        });
        assertThat(retail.served).singleElement().asString().endsWith(INVENTORY + " rows=1");
        var jti = retail.served.getFirst().split(" ")[0];
        assertThat(toolAudits()).singleElement().asString().startsWith("结果=成功").contains("条件=")
                .contains("store_id=S001").contains("行数=1").contains("令牌=" + jti).doesNotContain("鲜牛奶")
                .doesNotContain("eyJ");
        assertThat(output.getAll().lines().filter(line -> line.contains("event=tool_call ")))
                .singleElement().asString().contains("tool=" + INVENTORY).contains("rows=1").contains("jti=" + jti)
                .doesNotContain("鲜牛奶").doesNotContain("eyJ").doesNotContain("S001");

        var history = TestAsk.ask(http, staff, null, "再查一次");
        assertThat(dataCitations(history)).hasSize(1);
        var answerId = ((Number) history.get("id")).longValue();
        assertThat(jdbc.queryForObject("select conditions from answer_data_citation where answer_id = ?",
                String.class, answerId)).isEqualTo("store_id=S001，sku=A1001");
    }

    @Test
    @DisplayName("范围外的门店：不发请求，按“无权查看”拒答，回答里没有任何数据；审计记“拒绝”")
    void outOfScopeIsNotSent() {
        CHOICE.set(new ToolChoice(INVENTORY, Map.of("store_id", "S002", "sku", "A1001")));

        var answer = ask();

        assertThat(answer).containsEntry("refused", true).containsEntry("refusalReason", "DATA_DENIED");
        assertThat((String) answer.get("text")).contains("无权查看").doesNotContain("quantity");
        assertThat(dataCitations(answer)).isEmpty();
        assertThat(retail.served).isEmpty();
        assertThat(toolAudits()).singleElement().asString().startsWith("结果=拒绝").contains("store_id=S002");
    }

    @Test
    @DisplayName("模型选了没授权的工具：不调用，不发请求")
    void ungrantedToolIsNotCalled() {
        CHOICE.set(new ToolChoice("get_store_sales", Map.of("store_id", "S001")));

        var answer = ask();

        assertThat(answer).containsEntry("refused", true);
        assertThat(dataCitations(answer)).isEmpty();
        assertThat(retail.served).isEmpty();
    }

    @Test
    @DisplayName("模拟服务多返回一行范围外门店的数据（排在最前）：这行被丢弃，回答和响应里都没有，审计记丢弃 1 行")
    void dropsLeakedRows() {
        retail.fault = RetailMcpServer.Fault.LEAK;

        var answer = ask();

        assertThat(answer).containsEntry("refused", false);
        assertThat(answer.toString()).doesNotContain("S002").doesNotContain("quantity=3");
        assertThat((String) answer.get("text")).contains("quantity=24");
        assertThat(toolAudits()).singleElement().asString().contains("行数=1").contains("丢弃=1");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "SLOW|数据查询暂时不可用，暂时查不到。|UNAVAILABLE",
            "STATUS_401|数据查询没有通过授权，暂时查不到。|REJECTED",
            "STATUS_403|数据查询没有通过授权，暂时查不到。|REJECTED",
            "STATUS_429|数据查询太频繁，请稍后再试。|RATE_LIMITED",
            "TOOL_ERROR|数据暂时查不到：库存系统维护中。|TOOL_ERROR:unavailable",
            "PROTOCOL_ERROR|数据查询的协议出错，暂时查不到。|PROTOCOL",
            "BAD_SCHEMA|数据查询返回的格式不对，暂时查不到。|PROTOCOL"})
    @DisplayName("工具失败（超时、401、403、429、isError、协议错误、不符合 outputSchema）：如实说查不到，不编数据、不用旧数据")
    void failuresAreReportedHonestly(RetailMcpServer.Fault fault, String message, String code) {
        assertThat(ask()).as("先成功查过一次").containsEntry("refused", false);
        retail.fault = fault;

        var answer = ask();

        assertThat(answer).containsEntry("refused", true).containsEntry("refusalReason", "DATA_UNAVAILABLE")
                .containsEntry("text", message);
        assertThat(dataCitations(answer)).isEmpty();
        assertThat(answer.toString()).doesNotContain("quantity");
        assertThat(toolAudits()).last().asString().startsWith("结果=失败").contains("状态=" + code);
    }

    @SuppressWarnings("unchecked") // 接口返回的都是 JSON 对象。
    private Map<String, Object> exchange(String token, HttpMethod method, String path, Object body) {
        var response = http.exchange(path, method, new HttpEntity<>(body, auth(token)), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + "：" + response.getBody()).isTrue();
        return response.getBody();
    }

    private static long id(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }

    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
