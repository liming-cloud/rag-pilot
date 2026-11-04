package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 先查重再写入的操作同时提交（#135）：数据库的唯一约束兜底，只有一个成功（201），其余都是 409 和对应的错误码，
 * 不出现 500。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-uniqueness-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class ConcurrentUniquenessTests {

    private static final int CALLERS = 8;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("同时建同名知识库：一个 201，其余 409 KNOWLEDGE_CONFLICT")
    void createsOneKnowledgeBase() throws Exception {
        var responses = concurrently(() -> post("/api/knowledge-bases", Map.of("name", "并发同名知识库")));

        assertOneCreated(responses, "KNOWLEDGE_CONFLICT");
    }

    @Test
    @DisplayName("同时往一个库上传同一份文件：一个 201，其余 409 KNOWLEDGE_CONFLICT")
    void storesOneCopyOfAFile() throws Exception {
        long kb = id(post("/api/knowledge-bases", Map.of("name", "并发上传知识库")));
        var content = "收银员交班时要清点备用金，在交班表上签字后再离开收银台。".getBytes(StandardCharsets.UTF_8);

        var responses = concurrently(() -> upload(kb, "收银规范.txt", content));

        assertOneCreated(responses, "KNOWLEDGE_CONFLICT");
    }

    @Test
    @DisplayName("同时建同名角色：一个 201，其余 409 IDENTITY_CONFLICT")
    void createsOneRole() throws Exception {
        var responses = concurrently(() -> post("/api/roles", Map.of("name", "并发角色", "permissions", List.of())));

        assertOneCreated(responses, "IDENTITY_CONFLICT");
    }

    @Test
    @DisplayName("同时把两个角色改成同一个名字：一个成功，另一个 409 IDENTITY_CONFLICT")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void renamesOneRole() throws Exception {
        long first = id(post("/api/roles", Map.of("name", "改名角色一", "permissions", List.of())));
        long second = id(post("/api/roles", Map.of("name", "改名角色二", "permissions", List.of())));
        var ids = List.of(first, second);
        var next = new java.util.concurrent.atomic.AtomicInteger();

        var responses = concurrently(2, () -> http.exchange("/api/roles/{id}", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("name", "改名后的角色")), Map.class, ids.get(next.getAndIncrement())));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .allSatisfy(response -> assertThat(response.getBody()).containsEntry("code", "IDENTITY_CONFLICT"));
    }

    @Test
    @DisplayName("同时建同名账号：一个 201，其余 409 IDENTITY_CONFLICT")
    void createsOneAccount() throws Exception {
        var responses = concurrently(() -> post("/api/users", Map.of("username", "concurrent01",
                "displayName", "并发店员", "password", "staff-password",
                "roleIds", List.of(TestLogin.staffRoleId(http)))));

        assertOneCreated(responses, "IDENTITY_CONFLICT");
    }

    @Test
    @DisplayName("同时添加同名模型服务商：一个 201，其余 409 SETTINGS_CONFLICT")
    void createsOneProvider() throws Exception {
        var responses = concurrently(() -> post("/api/model-providers",
                Map.of("name", "并发服务商", "baseUrl", "http://127.0.0.1:9/v1")));

        assertOneCreated(responses, "SETTINGS_CONFLICT");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertOneCreated(List<ResponseEntity<Map>> responses, String conflictCode) {
        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .as("只有 201 和 409，没有 500").containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .allSatisfy(response -> assertThat(response.getBody()).containsEntry("code", conflictCode));
    }

    @SuppressWarnings("rawtypes")
    private List<ResponseEntity<Map>> concurrently(Callable<ResponseEntity<Map>> call) throws Exception {
        return concurrently(CALLERS, call);
    }

    /** 让几个调用尽量同时发出，返回每个调用的响应。 */
    @SuppressWarnings("rawtypes")
    private static List<ResponseEntity<Map>> concurrently(int callers, Callable<ResponseEntity<Map>> call)
            throws Exception {
        var ready = new CountDownLatch(callers);
        var go = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<ResponseEntity<Map>>>();
            for (int i = 0; i < callers; i++) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            var responses = new ArrayList<ResponseEntity<Map>>();
            for (var future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        return http.postForEntity(path, body, Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> upload(long kb, String filename, byte[] content) {
        var file = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers), Map.class,
                kb);
    }

    @SuppressWarnings("rawtypes")
    private static long id(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }
}
