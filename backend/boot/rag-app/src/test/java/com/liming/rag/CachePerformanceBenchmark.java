package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.settings.application.model.ModelPurpose;
import com.liming.rag.identity.application.role.AccessService;
import com.liming.rag.settings.application.model.ModelSettingsService;
import com.liming.rag.settings.application.system.SystemSettingsService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 缓存的耗时测量（#145 第 9 步，D-054），默认不跑（标签 benchmark），手动运行：
 * {@code ./mvnw verify -pl backend/boot/rag-app -am -Dtest.excludedGroups=eval -Dtest='CachePerformanceBenchmark*'
 * -Dsurefire.failIfNoSpecifiedTests=false}。两个子类分别测有 Redis 和本地模式，结果打印在输出里（p50、p95，毫秒）。
 * 只用来比较改动前后，数字随机器变化，不作断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-benchmark-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
@Tag("benchmark")
abstract class CachePerformanceBenchmark {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AccessService access;

    @Autowired
    private SystemSettingsService settings;

    @Autowired
    private ModelSettingsService models;

    @Test
    void measure() {
        TestLogin.asAdmin(http);
        long admin = ((Number) http.getForObject("/api/auth/me", Map.class).get("id")).longValue();
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "测量知识库"), Map.class);
        long store = ((Number) created.getBody().get("id")).longValue();
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");

        report("服务层：权限点", 2000, () -> access.permissionsOf(admin));
        report("服务层：系统设置", 2000, () -> settings.chatTexts());
        report("服务层：模型路由", 2000, () -> models.savedEndpoint(ModelPurpose.ANSWER));
        report("GET /api/users", 300, () -> http.getForEntity("/api/users", Map.class));
        report("一次问答", 50, () -> TestAsk.ask(http, null, List.of(store), "青柚气泡水在哪个货架"));
    }

    private void report(String name, int times, Supplier<?> call) {
        for (int i = 0; i < Math.min(times / 5, 50); i++) {
            assertThat(call.get()).isNotNull();
        }
        var nanos = new long[times];
        for (int i = 0; i < times; i++) {
            long started = System.nanoTime();
            call.get();
            nanos[i] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        System.out.printf("benchmark mode=%s case=%s n=%d p50=%.3fms p95=%.3fms%n", getClass().getSimpleName(), name,
                times, nanos[times / 2] / 1e6, nanos[(int) (times * 0.95)] / 1e6);
    }

    private void upload(long knowledgeBaseId, String filename, String text) {
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
        var response = http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers),
                Map.class, knowledgeBaseId);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), null))
                .containsEntry("status", "READY");
    }

    /** 有 Redis：一级加二级，会话在 Redis。 */
    static class WithRedis extends CachePerformanceBenchmark {
    }

    /** 本地模式：只有一级，会话在进程内。 */
    @TestRedis.WithoutRedis
    static class Local extends CachePerformanceBenchmark {
    }
}
