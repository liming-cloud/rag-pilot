package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 前后端接口契约（#145 C7）：造一套数据，按管理员调用前端用到的查询接口，把响应换成“形状”（字段名和值的类型，不含具体值），
 * 连同权限码清单写进 {@value #SHAPES}。前端测试拿它校验 MSW 假数据和权限码常量，假数据和真接口对不上时前端测试失败。
 *
 * <p>形状和仓库里的文件不一致时这里失败：接口改了就用 {@code -Dcontract.update=true} 重新生成，再跑前端测试，
 * 按提示改前端类型和假数据，三者一起提交。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-contract-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class ApiContractTests {

    static final String SHAPES = "frontend/src/test/contract/api-shapes.json";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("查询接口的响应形状和权限码清单与 " + SHAPES + " 一致")
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
        paths.forEach((name, path) -> endpoints.put(name, shape(get(path))));

        var permissions = new TreeSet<String>();
        get("/api/permissions").findValues("code").forEach(code -> permissions.add(code.asString()));

        var document = new LinkedHashMap<String, Object>();
        document.put("说明", "由后端 ApiContractTests 生成，不要手改；接口变了用 -Dcontract.update=true 重新生成");
        document.put("permissions", List.copyOf(permissions));
        document.put("endpoints", endpoints);
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

    /** 造一套数据，让各接口的列表都不为空、可选字段尽量有值。 */
    @SuppressWarnings("unchecked")
    private Seeded seed() {
        var role = post("/api/roles", Map.of("name", "契约店长", "permissions", List.of("kb:view", "doc:upload")));
        long roleId = id(role);
        long user = id(post("/api/users", Map.of("username", "contract01", "displayName", "契约店员",
                "password", "staff-password", "roleIds", List.of(TestLogin.staffRoleId(http), roleId))));
        long kb = id(post("/api/knowledge-bases", Map.of("name", "契约知识库")));
        exchange(HttpMethod.PUT, "/api/knowledge-bases/" + kb + "/members/" + user, null);
        exchange(HttpMethod.PUT, "/api/knowledge-bases/" + kb + "/role-grants/" + roleId, null);

        var ready = TestDocuments.awaitProcessed(http, kb, upload(kb, "收银规范.txt",
                "收银员交班时要清点备用金，在交班表上签字后再离开收银台。"), null);
        assertThat(ready).containsEntry("status", "READY");
        var replacement = TestDocuments.awaitProcessed(http, kb, upload(kb + "/documents/" + ready.get("id")
                + "/versions", "收银规范-新版.txt", "收银员交班时要清点备用金和票据，在交班表上签字后再离开收银台。"), null);
        assertThat(replacement).containsEntry("status", "READY");
        exchange(HttpMethod.PUT, "/api/knowledge-bases/" + kb + "/documents/" + replacement.get("id") + "/validity",
                Map.of("effectiveFrom", "", "expiresOn", LocalDate.now().plusDays(3).toString()));
        TestDocuments.awaitProcessed(http, kb, upload(kb, "空白.txt", " \n\n  \n"), null);

        long conversation = TestAsk.conversation(http, null, List.of(kb));
        var answered = TestAsk.answer(TestAsk.stream(http, null, conversation, "交班时要做什么"));
        TestAsk.stream(http, null, conversation, "保险柜密码是多少");
        exchange(HttpMethod.PUT, "/api/answers/" + answered.get("id") + "/feedback",
                Map.of("rating", "DOWN", "comment", "没说要签字"));
        exchange(HttpMethod.PUT, "/api/settings/CHAT_PLACEHOLDER", Map.of("value", "问点门店规范"));
        return new Seeded(kb, conversation);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        var response = http.postForEntity(path, body, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + "：" + response.getBody()).isTrue();
        return response.getBody();
    }

    private void exchange(HttpMethod method, String path, Object body) {
        var response = http.exchange(path, method, new HttpEntity<>(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + "：" + response.getBody()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(Object kbPath, String filename, String text) {
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
        var path = kbPath instanceof Long ? "/api/knowledge-bases/" + kbPath + "/documents"
                : "/api/knowledge-bases/" + kbPath;
        var response = http.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).as(path + "：" + response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static long id(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }
}
