package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.common.ModelCallException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 前后端接口契约（#145 C7、#201）：按管理员调用前端用到的接口，把响应换成“形状”（字段名和值的类型，不含具体值），
 * 连同权限码清单写进 {@value #SHAPES}：{@code endpoints} 是查询接口，{@code writes} 是造数据时调的写接口（状态码和响应形状），
 * {@code events} 是流式提问的每种 SSE 事件。前端测试拿它校验 MSW 假数据和权限码常量，假数据和真接口对不上时前端测试失败。
 *
 * <p>形状和仓库里的文件不一致时这里失败：接口改了就用 {@code -Dcontract.update=true} 重新生成，再跑前端测试，
 * 按提示改前端类型和假数据，三者一起提交。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rag.knowledge.storage.root=${java.io.tmpdir}/rag-contract-test-files",
            // 保存服务商密钥要有主密钥（32 字节的 Base64）。
            "rag.settings.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureTestRestTemplate
@Testcontainers
class ApiContractTests {

    static final String SHAPES = "frontend/src/test/contract/api-shapes.json";

    /** 只让这个问题的向量化失败，流式提问才会发 {@code error} 事件。 */
    private static final String MODEL_DOWN = "模型不可用时怎么办";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestRestTemplate http;

    @MockitoSpyBean
    private EmbeddingClient embeddings;

    private final Map<String, Object> writes = new TreeMap<>();
    private final Map<String, Object> events = new TreeMap<>();

    @Test
    @DisplayName("查询、写接口和 SSE 事件的形状以及权限码清单与 " + SHAPES + " 一致")
    void responseShapesMatchCommittedFile() throws IOException {
        TestLogin.asAdmin(http);
        var data = seed();

        var endpoints = new TreeMap<String, Object>();
        var paths = new LinkedHashMap<String, String>();
        paths.put("GET /api/auth/branding", "/api/auth/branding");
        paths.put("GET /api/auth/me", "/api/auth/me");
        paths.put("GET /api/permissions", "/api/permissions");
        paths.put("GET /api/roles", "/api/roles");
        paths.put("GET /api/users", "/api/users");
        paths.put("GET /api/knowledge-bases", "/api/knowledge-bases");
        paths.put("GET /api/knowledge-bases/page", "/api/knowledge-bases/page");
        paths.put("GET /api/knowledge-bases/expiry-summary", "/api/knowledge-bases/expiry-summary");
        paths.put("GET /api/knowledge-bases/:id", "/api/knowledge-bases/" + data.kb);
        paths.put("GET /api/knowledge-bases/:id/documents", "/api/knowledge-bases/" + data.kb + "/documents");
        paths.put("GET /api/knowledge-bases/:id/members", "/api/knowledge-bases/" + data.kb + "/members");
        paths.put("GET /api/knowledge-bases/:id/role-grants", "/api/knowledge-bases/" + data.kb + "/role-grants");
        paths.put("GET /api/conversations", "/api/conversations");
        paths.put("GET /api/conversations/:id", "/api/conversations/" + data.conversation);
        paths.put("GET /api/answers", "/api/answers?filter=all&status=all");
        paths.put("GET /api/answers/gaps", "/api/answers/gaps?days=30&status=all");
        paths.put("GET /api/answers/stats", "/api/answers/stats");
        paths.put("GET /api/models", "/api/models");
        paths.put("GET /api/models/changes", "/api/models/changes");
        paths.put("GET /api/settings", "/api/settings");
        paths.put("GET /api/settings/chat", "/api/settings/chat");
        paths.put("GET /api/audit-logs", "/api/audit-logs");
        paths.put("GET /api/data-grants", "/api/data-grants?granteeType=USER&granteeId=" + get("/api/auth/me").get("id").asLong());
        paths.forEach((name, path) -> endpoints.put(name, shape(get(path))));

        var permissions = new TreeSet<String>();
        get("/api/permissions").findValues("code").forEach(code -> permissions.add(code.asString()));

        var document = new LinkedHashMap<String, Object>();
        document.put("说明", "由后端 ApiContractTests 生成，不要手改；接口变了用 -Dcontract.update=true 重新生成");
        document.put("permissions", List.copyOf(permissions));
        document.put("endpoints", endpoints);
        document.put("writes", writes);
        document.put("events", events);
        var generated = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";

        var file = BackendTree.root().getParent().resolve(SHAPES);
        if (Boolean.getBoolean("contract.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(generated)) {
            fail(SHAPES + " 与当前接口不一致：运行 ./mvnw verify -pl backend/boot/rag-app -Dtest=ApiContractTests "
                    + "-Dcontract.update=true 重新生成，再在 frontend/ 下跑 npm test，按提示改前端类型和假数据");
        }
    }

    private record Seeded(long kb, long conversation) {
    }

    /** 造一套数据，让各接口的列表都不为空、可选字段尽量有值；用到的写接口顺手记下形状。 */
    private Seeded seed() {
        long roleId = id(write("POST /api/roles", HttpMethod.POST, "/api/roles",
                Map.of("name", "契约店长", "permissions", List.of("kb:view"))));
        write("PATCH /api/roles/:id", HttpMethod.PATCH, "/api/roles/" + roleId,
                Map.of("name", "契约店长", "permissions", List.of("kb:view", "doc:upload")));
        long spareRole = id(post("/api/roles", Map.of("name", "契约临时", "permissions", List.of("kb:view"))));
        write("DELETE /api/roles/:id", HttpMethod.DELETE, "/api/roles/" + spareRole, null);
        long user = seedUsers(roleId);
        long kb = id(write("POST /api/knowledge-bases", HttpMethod.POST, "/api/knowledge-bases",
                Map.of("name", "契约知识库")));
        write("PUT /api/knowledge-bases/:id/members/:userId", HttpMethod.PUT,
                "/api/knowledge-bases/" + kb + "/members/" + user, null);
        write("PUT /api/knowledge-bases/:id/role-grants/:roleId", HttpMethod.PUT,
                "/api/knowledge-bases/" + kb + "/role-grants/" + roleId, null);
        seedDocuments(kb);
        long conversation = seedConversation(kb);
        seedSettings();
        return new Seeded(kb, conversation);
    }

    private long seedUsers(long roleId) {
        long user = id(write("POST /api/users", HttpMethod.POST, "/api/users", Map.of("username", "contract01",
                "displayName", "契约店员", "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http)))));
        write("POST /api/auth/login", HttpMethod.POST, "/api/auth/login",
                Map.of("username", "contract01", "password", "staff-password"));
        write("PUT /api/users/:id/roles", HttpMethod.PUT, "/api/users/" + user + "/roles",
                Map.of("roleIds", List.of(TestLogin.staffRoleId(http), roleId)));
        write("PUT /api/users/:id/enabled", HttpMethod.PUT, "/api/users/" + user + "/enabled", Map.of("enabled", true));
        write("POST /api/users/:id/reset-password", HttpMethod.POST, "/api/users/" + user + "/reset-password",
                Map.of("password", "staff-password-2"));
        write("POST /api/users/:id/unlock", HttpMethod.POST, "/api/users/" + user + "/unlock", null);
        var rows = List.of(Map.of("line", 2, "username", "contract01", "displayName", "重名", "roleNames", List.of("契约店长")),
                Map.of("line", 3, "username", "contract02", "displayName", "契约导入", "roleNames", List.of("契约店长")));
        write("POST /api/users/import/check", HttpMethod.POST, "/api/users/import/check", Map.of("rows", rows));
        write("POST /api/users/import", HttpMethod.POST, "/api/users/import",
                Map.of("rows", List.of(rows.getLast()), "password", "import-password"));
        return user;
    }

    private void seedDocuments(long kb) {
        var ready = TestDocuments.awaitProcessed(http, kb, upload("POST /api/knowledge-bases/:id/documents",
                kb + "/documents", "收银规范.txt", "收银员交班时要清点备用金，在交班表上签字后再离开收银台。"), null);
        assertThat(ready).containsEntry("status", "READY");
        var replacement = TestDocuments.awaitProcessed(http, kb, upload(
                "POST /api/knowledge-bases/:id/documents/:documentId/versions", kb + "/documents/" + ready.get("id")
                + "/versions", "收银规范-新版.txt", "收银员交班时要清点备用金和票据，在交班表上签字后再离开收银台。"), null);
        assertThat(replacement).containsEntry("status", "READY");
        write("PUT /api/knowledge-bases/:id/documents/:documentId/validity", HttpMethod.PUT,
                "/api/knowledge-bases/" + kb + "/documents/" + replacement.get("id") + "/validity",
                Map.of("effectiveFrom", "", "expiresOn", LocalDate.now().plusDays(3).toString()));
        TestDocuments.awaitProcessed(http, kb, upload(null, kb + "/documents", "空白.txt", " \n\n  \n"), null);
        // 只有模型不可用导致失败的文档能重新处理：入库时让向量化失败一次。
        doThrow(new ModelCallException("向量模型暂时不可用")).when(embeddings).embed(anyList());
        var failed = TestDocuments.awaitProcessed(http, kb, upload(null, kb + "/documents", "盘点规范.txt",
                "月末盘点要两人一组，一人点数一人记录。"), null);
        doCallRealMethod().when(embeddings).embed(anyList());
        assertThat(failed).containsEntry("status", "FAILED");
        write("POST /api/knowledge-bases/:id/documents/:documentId/reprocess", HttpMethod.POST,
                "/api/knowledge-bases/" + kb + "/documents/" + failed.get("id") + "/reprocess", null);
        TestDocuments.awaitProcessed(http, kb, failed, null);
        var spare = TestDocuments.awaitProcessed(http, kb, upload(null, kb + "/documents", "临时.txt", " \n"), null);
        write("DELETE /api/knowledge-bases/:id/documents/:documentId", HttpMethod.DELETE,
                "/api/knowledge-bases/" + kb + "/documents/" + spare.get("id"), null);
    }

    private long seedConversation(long kb) {
        long conversation = id(write("POST /api/conversations", HttpMethod.POST, "/api/conversations",
                Map.of("knowledgeBaseIds", List.of(kb))));
        write("PATCH /api/conversations/:id", HttpMethod.PATCH, "/api/conversations/" + conversation,
                Map.of("title", "交班"));
        long spare = TestAsk.conversation(http, null, List.of(kb));
        write("DELETE /api/conversations/:id", HttpMethod.DELETE, "/api/conversations/" + spare, null);

        doThrow(new ModelCallException("向量模型暂时不可用")).when(embeddings).embedQuestion(MODEL_DOWN);
        var answered = TestAsk.answer(stream(conversation, "交班时要做什么"));
        var refused = TestAsk.answer(stream(conversation, "保险柜密码是多少"));
        var handled = TestAsk.answer(stream(conversation, "门禁卡丢了找谁补办"));
        assertThat(stream(conversation, MODEL_DOWN).getLast().name()).as("模型不可用时发 error").isEqualTo("error");

        write("PUT /api/answers/:id/feedback", HttpMethod.PUT, "/api/answers/" + answered.get("id") + "/feedback",
                Map.of("rating", "DOWN", "comment", "没说要签字"));
        exchange(HttpMethod.PUT, "/api/answers/" + refused.get("id") + "/feedback", Map.of("rating", "UP"));
        write("DELETE /api/answers/:id/feedback", HttpMethod.DELETE, "/api/answers/" + refused.get("id") + "/feedback",
                null);
        write("POST /api/answers/:id/citations/:index/opened", HttpMethod.POST,
                "/api/answers/" + answered.get("id") + "/citations/1/opened", null);
        write("PATCH /api/answers/:id/review", HttpMethod.PATCH, "/api/answers/" + answered.get("id") + "/review",
                Map.of("status", "RESOLVED", "note", "已补签字要求"));
        write("POST /api/answers/:id/recheck", HttpMethod.POST, "/api/answers/" + answered.get("id") + "/recheck",
                null);
        write("POST /api/answers/gaps/resolve", HttpMethod.POST, "/api/answers/gaps/resolve",
                Map.of("recordIds", List.of(handled.get("id")), "note", "门禁卡找物业补办"));
        return conversation;
    }

    private void seedSettings() {
        write("PUT /api/settings/:key", HttpMethod.PUT, "/api/settings/CHAT_PLACEHOLDER", Map.of("value", "问点门店规范"));
        exchange(HttpMethod.PUT, "/api/settings/LOGIN_HINT", Map.of("value", "临时提示"));
        write("DELETE /api/settings/:key", HttpMethod.DELETE, "/api/settings/LOGIN_HINT", null);
        long provider = id(write("POST /api/model-providers", HttpMethod.POST, "/api/model-providers",
                Map.of("name", "契约服务商", "baseUrl", "http://127.0.0.1:9/v1", "apiKey", "sk-contract-0000")));
        write("PATCH /api/model-providers/:id", HttpMethod.PATCH, "/api/model-providers/" + provider,
                Map.of("name", "契约服务商二", "baseUrl", "http://127.0.0.1:9/v1", "apiKey", ""));
        write("DELETE /api/model-providers/:id", HttpMethod.DELETE, "/api/model-providers/" + provider, null);
        write("PUT /api/models/prices", HttpMethod.PUT, "/api/models/prices",
                Map.of("model", "extractive", "inputPerMillion", 2, "outputPerMillion", 8));
        long me = get("/api/auth/me").get("id").asLong();
        write("PUT /api/data-grants", HttpMethod.PUT, "/api/data-grants?granteeType=USER&granteeId=" + me,
                Map.of("tools", List.of(), "units", List.of(), "regions", List.of()));
    }

    /** 流式提问，把每种事件的数据形状合并记进 {@code events}。 */
    private List<SseEvents.Event> stream(long conversation, String question) {
        var received = TestAsk.stream(http, null, conversation, question);
        received.forEach(event -> events.merge(event.name(), shape(JSON.readTree(event.data())),
                ApiContractTests::merge));
        return received;
    }

    /** 调一个写接口，要求 2xx，把状态码和响应形状（有响应体时）记进 {@code writes}，返回响应体（没有时为 null）。 */
    private JsonNode write(String name, HttpMethod method, String path, Object body) {
        var request = body instanceof HttpEntity<?> entity ? entity : new HttpEntity<>(body);
        var response = http.exchange(path, method, request, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(name + "：" + response.getBody()).isTrue();
        var recorded = new TreeMap<String, Object>();
        recorded.put("status", response.getStatusCode().value());
        if (response.getBody() == null) {
            writes.put(name, recorded);
            return null;
        }
        var node = JSON.readTree(response.getBody());
        recorded.put("body", shape(node));
        writes.put(name, recorded);
        return node;
    }

    /** 形状：对象换成字段名到形状（按名排序），数组合并各元素的形状，其余换成类型名；空数组记为 []。 */
    static Object shape(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, Object>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), shape(entry.getValue())));
            return fields;
        }
        if (node.isArray()) {
            Object merged = null;
            for (var element : node) {
                merged = merge(merged, shape(element));
            }
            return merged == null ? List.of() : List.of(merged);
        }
        if (node.isNull()) {
            return "null";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "string";
    }

    /** 数组里不同元素的形状合在一起：字段取并集，某个元素里是 null 的取另一个的类型。 */
    @SuppressWarnings("unchecked")
    private static Object merge(Object left, Object right) {
        if (left == null || "null".equals(left)) {
            return right;
        }
        if ("null".equals(right)) {
            return left;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            var merged = new TreeMap<String, Object>((Map<String, Object>) a);
            ((Map<String, Object>) b).forEach((key, value) -> merged.merge(key, value, ApiContractTests::merge));
            return merged;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.isEmpty()) {
                return b;
            }
            return b.isEmpty() ? a : List.of(merge(a.getFirst(), b.getFirst()));
        }
        return left;
    }

    private JsonNode get(String path) {
        var response = http.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path + "：" + response.getBody()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private JsonNode post(String path, Object body) {
        var response = http.postForEntity(path, body, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + "：" + response.getBody()).isTrue();
        return JSON.readTree(response.getBody());
    }

    private void exchange(HttpMethod method, String path, Object body) {
        var response = http.exchange(path, method, new HttpEntity<>(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + "：" + response.getBody()).isTrue();
    }

    /** 上传到 {@code /api/knowledge-bases/{kbPath}}；{@code name} 不为 null 时记进 {@code writes}。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(String name, String kbPath, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var request = new HttpEntity<>(body, headers);
        var path = "/api/knowledge-bases/" + kbPath;
        if (name != null) {
            return JSON.convertValue(write(name, HttpMethod.POST, path, request), Map.class);
        }
        var response = http.postForEntity(path, request, Map.class);
        assertThat(response.getStatusCode()).as(path + "：" + response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static long id(JsonNode body) {
        return body.get("id").asLong();
    }
}
