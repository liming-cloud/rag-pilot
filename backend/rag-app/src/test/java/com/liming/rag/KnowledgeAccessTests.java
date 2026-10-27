package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 知识库成员授权：员工只能看到、问到自己是成员的知识库；请求里点名无权限的库也查不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.storage.root=${java.io.tmpdir}/rag-access-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class KnowledgeAccessTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {
            };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("授权前看不到也问不到；授权后只能问到被授权的库；撤销后又看不到")
    void staffSeesOnlyGrantedKnowledgeBases() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long store = createKnowledgeBase(admin, "授权测试门店库");
        long manager = createKnowledgeBase(admin, "授权测试店长库");
        upload(admin, store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");
        upload(admin, manager, "店长奖金.txt", "店长季度奖金按门店毛利达成率计算，达成 100% 发放基数的 1.2 倍。");
        long staffId = createStaff(admin, "access01");
        String staff = TestLogin.token(http, "access01", "staff-password");

        assertThat(list(staff, "/api/knowledge-bases")).isEmpty();
        assertThat(ask(staff, "青柚气泡水在哪个货架", null)).containsEntry("refused", true);

        assertThat(send(HttpMethod.PUT, "/api/knowledge-bases/" + store + "/members/999999", admin))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PUT, "/api/knowledge-bases/" + store + "/members/" + staffId, staff))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PUT, "/api/knowledge-bases/" + store + "/members/" + staffId, admin))
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(list(staff, "/api/knowledge-bases")).extracting(kb -> kb.get("name")).containsExactly("授权测试门店库");
        assertThat(list(admin, "/api/knowledge-bases/" + store + "/members"))
                .extracting(member -> ((Number) member.get("userId")).longValue()).contains(staffId);
        assertThat(ask(staff, "青柚气泡水在哪个货架", null)).containsEntry("refused", false);

        var probe = ask(staff, "店长季度奖金怎么计算", List.of(store, manager));
        assertThat(probe.toString()).doesNotContain("店长奖金.txt").doesNotContain("1.2 倍");
        assertThat(send(HttpMethod.GET, "/api/knowledge-bases/" + manager + "/documents", staff))
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(send(HttpMethod.DELETE, "/api/knowledge-bases/" + store + "/members/" + staffId, admin))
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(staff, "/api/knowledge-bases")).isEmpty();
    }

    private long createKnowledgeBase(String token, String name) {
        var created = http.exchange("/api/knowledge-bases", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name), auth(token)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private long createStaff(String admin, String username) {
        var created = http.exchange("/api/users", HttpMethod.POST, new HttpEntity<>(Map.of("username", username,
                "displayName", "店员", "password", "staff-password", "role", "STAFF"), auth(admin)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private void upload(String token, long knowledgeBaseId, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/knowledge-bases/{id}/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class, knowledgeBaseId);
        assertThat(response.getBody()).containsEntry("status", "READY");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ask(String token, String question, List<Long> knowledgeBaseIds) {
        var body = new java.util.HashMap<String, Object>();
        body.put("question", question);
        body.put("knowledgeBaseIds", knowledgeBaseIds);
        var response = http.exchange("/api/ask", HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<Map<String, Object>> list(String token, String path) {
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), LIST);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpStatusCode send(HttpMethod method, String path, String token) {
        return http.exchange(path, method, new HttpEntity<>(auth(token)), String.class).getStatusCode();
    }

    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
