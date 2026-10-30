package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 员工端会话（#107、D-036）：只能操作自己的会话；知识库按会话选、只能选已授权的库；按会话提问、停止、重答；
 * 删除只影响员工端，回答记录保留。模型用本地确定性实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-conversation-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class ConversationTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    private String admin;

    @BeforeEach
    void signIn() {
        admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
    }

    @Test
    @DisplayName("新会话默认选中全部已授权的库；点名无权限的库被忽略；列表只有自己的会话")
    void createsConversationWithAccessibleKnowledgeBases() {
        long store = createKnowledgeBase("会话测试门店库");
        long manager = createKnowledgeBase("会话测试店长库");
        long staffId = createStaff("conv01");
        grant(store, staffId);
        String staff = TestLogin.token(http, "conv01", "staff-password");

        var byDefault = exchange(HttpMethod.POST, "/api/conversations", Map.of(), staff);
        assertThat(byDefault.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ids(byDefault.getBody().get("knowledgeBaseIds"))).containsExactly(store);
        assertThat(byDefault.getBody()).containsEntry("title", "新对话");

        var probing = exchange(HttpMethod.POST, "/api/conversations", Map.of("knowledgeBaseIds", List.of(store, manager)), staff);
        assertThat(ids(probing.getBody().get("knowledgeBaseIds"))).containsExactly(store);

        assertThat(list(staff)).hasSize(2);
        assertThat(list(admin)).extracting(conversation -> conversation.get("id"))
                .doesNotContain(byDefault.getBody().get("id"), probing.getBody().get("id"));
    }

    @Test
    @DisplayName("别人的会话看不到、改不了、删不掉，一律返回 404")
    void othersConversationsAreNotFound() {
        long store = createKnowledgeBase("会话测试越权库");
        long ownerId = createStaff("conv02");
        long otherId = createStaff("conv03");
        grant(store, ownerId);
        grant(store, otherId);
        String owner = TestLogin.token(http, "conv02", "staff-password");
        String other = TestLogin.token(http, "conv03", "staff-password");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), owner));
        String path = "/api/conversations/" + id;

        assertThat(exchange(HttpMethod.GET, path, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.PATCH, path, Map.of("title", "偷改"), other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.DELETE, path, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.GET, path, null, owner).getBody()).containsEntry("title", "新对话");
    }

    @Test
    @DisplayName("重命名、改选知识库（只能选已授权的库）、删除")
    void renamesSelectsAndDeletes() {
        long store = createKnowledgeBase("会话测试改选库一");
        long goods = createKnowledgeBase("会话测试改选库二");
        long manager = createKnowledgeBase("会话测试改选库三");
        long staffId = createStaff("conv04");
        grant(store, staffId);
        grant(goods, staffId);
        String staff = TestLogin.token(http, "conv04", "staff-password");
        long id = id(exchange(HttpMethod.POST, "/api/conversations", Map.of(), staff));
        String path = "/api/conversations/" + id;

        var renamed = exchange(HttpMethod.PATCH, path, Map.of("title", "  冷柜温度  "), staff);
        assertThat(renamed.getBody()).containsEntry("title", "冷柜温度");
        assertThat(exchange(HttpMethod.PATCH, path, Map.of("title", "长".repeat(41)), staff).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var narrowed = exchange(HttpMethod.PATCH, path, Map.of("knowledgeBaseIds", List.of(goods, manager)), staff);
        assertThat(ids(narrowed.getBody().get("knowledgeBaseIds"))).containsExactly(goods);
        assertThat(exchange(HttpMethod.PATCH, path, Map.of("knowledgeBaseIds", List.of(manager)), staff).getStatusCode())
                .as("全是无权限的库时不能保存，否则会话没有可检索的库").isEqualTo(HttpStatus.BAD_REQUEST);

        var detail = exchange(HttpMethod.GET, path, null, staff).getBody();
        assertThat(detail.get("knowledgeBases").toString()).contains("会话测试改选库二").doesNotContain("会话测试改选库三");

        assertThat(exchange(HttpMethod.DELETE, path, null, staff).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(HttpMethod.GET, path, null, staff).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private long createKnowledgeBase(String name) {
        return id(exchange(HttpMethod.POST, "/api/knowledge-bases", Map.of("name", name), admin));
    }

    private long createStaff(String username) {
        return id(exchange(HttpMethod.POST, "/api/users", Map.of("username", username, "displayName", "店员",
                "password", "staff-password", "role", "STAFF"), admin));
    }

    private void grant(long knowledgeBaseId, long userId) {
        var response = http.exchange("/api/knowledge-bases/{id}/members/{userId}", HttpMethod.PUT,
                new HttpEntity<>(auth(admin)), Void.class, knowledgeBaseId, userId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    void upload(long knowledgeBaseId, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = auth(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/knowledge-bases/{id}/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class, knowledgeBaseId);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), admin))
                .containsEntry("status", "READY");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path, Object body, String token) {
        var headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return (ResponseEntity) http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private List<Map<String, Object>> list(String token) {
        return http.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(auth(token)), LIST).getBody();
    }

    private static long id(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }

    private static List<Long> ids(Object values) {
        return ((List<?>) values).stream().map(value -> ((Number) value).longValue()).toList();
    }

    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
