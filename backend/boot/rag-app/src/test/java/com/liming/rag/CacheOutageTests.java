package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.ask.AnswerService;
import com.liming.rag.identity.application.role.AccessService;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;

/**
 * 缓存降级（D-053）：Redis 停掉后，问答用到的读取（权限和角色、系统设置、模型路由、问题向量）都直接读库，回答照常，
 * 停掉期间改的权限也立即生效。会话存在 Redis 里，停掉后接口登录不了（D-053 写明不降级），所以停掉之后直接调问答的用例服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rag.cache.retry-after=1h"})
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class CacheOutageTests {

    private static final TenantId TENANT = new TenantId(1);

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AnswerService answers;

    @Autowired
    private AccessService access;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier(TestRedis.BEAN)
    private GenericContainer<?> redis;

    @Test
    @DisplayName("Redis 停掉后问答照常、带引用；停掉期间改了权限，一级缓存照样清除、下一次按库里判定；记下 cache_failure")
    void answersWhileRedisIsDown(CapturedOutput output) {
        TestLogin.asAdmin(http);
        long admin = ((Number) http.getForObject("/api/auth/me", Map.class).get("id")).longValue();
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "降级知识库"), Map.class);
        long store = ((Number) created.getBody().get("id")).longValue();
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");
        var question = "青柚气泡水在哪个货架";
        // 先问一次，把权限、设置、路由和问题向量都放进缓存。
        assertThat(TestAsk.ask(http, null, List.of(store), question)).containsEntry("refused", false);

        redis.stop();

        var answered = answers.ask(TENANT, new UserId(admin), List.of(store), question);
        assertThat(answered.refused()).isFalse();
        assertThat(answered.text()).contains("03 号货架");
        assertThat(answered.citations()).isNotEmpty();
        assertThat(output).contains("event=cache_failure");

        // 停掉期间停用（库里改，再像用例那样清除）：Redis 里的清除做不成，一级缓存照样清掉，不会用到停掉前缓存的权限。
        jdbc.update("update app_user set enabled = false where id = ?", admin);
        access.forget(admin);
        assertThat(access.permissionsOf(admin)).isEmpty();
        assertThat(access.exists(TENANT, new UserId(admin))).isFalse();
        assertThat(access.roleIds(TENANT, new UserId(admin))).isEmpty();
    }

    @SuppressWarnings("unchecked") // 上传响应按原始 Map 读。
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
}
