package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.ask.Rewrite;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 出了问题看得出来（#145 C1）：Actuator 开放指标；拒答、低分改写、入库失败、登录失败和锁定、越权拒绝各留一行 {@code event=…} 日志，
 * 不记密码和问题原文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AnswerLogService answers;

    @Test
    @DisplayName("Actuator 开放指标：有接口请求的统计")
    @SuppressWarnings("unchecked")
    void exposesMetrics() {
        http.getForEntity("/actuator/health", Map.class);

        var metrics = http.getForEntity("/actuator/metrics", Map.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) metrics.getBody().get("names")).contains("http.server.requests", "jvm.memory.used");
        assertThat(http.getForEntity("/actuator/env", Map.class).getStatusCode()).as("其他端点不开放")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("登录输错留下 login_failed，连续输错到上限留下 account_locked；日志里没有输错的密码")
    void logsLoginFailuresWithoutPasswords(CapturedOutput output) {
        TestLogin.asAdmin(http);
        createStaff("observe01");

        for (int i = 0; i < 5; i++) {
            http.postForEntity("/api/auth/login", Map.of("username", "observe01", "password", "wrong-pass-" + i),
                    Map.class);
        }

        assertThat(output.getAll()).contains("event=login_failed username=observe01")
                .contains("event=account_locked username=observe01").doesNotContain("wrong-pass-");
    }

    @Test
    @DisplayName("没有权限点、不能管理的库都留下 access_denied，带上用户和缺的权限或库")
    void logsAccessDenied(CapturedOutput output) {
        TestLogin.asAdmin(http);
        long kb = createKnowledgeBase("观测知识库");
        createStaff("observe02");
        var staff = bearer(TestLogin.token(http, "observe02", "staff-password"));

        var noPermission = http.exchange("/api/knowledge-bases", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "店员建的库"), staff), Map.class);
        assertThat(noPermission.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(output.getAll()).containsPattern("event=access_denied user=\\d+ permission=kb:create");

        var created = http.postForEntity("/api/roles", Map.of("name", "观测店长", "permissions", List.of("kb:view")),
                Map.class);
        long role = ((Number) created.getBody().get("id")).longValue();
        long manager = createUser("observe03", role);
        var managerToken = bearer(TestLogin.token(http, "observe03", "staff-password"));
        var notGranted = http.exchange("/api/knowledge-bases/{kb}/documents", HttpMethod.GET,
                new HttpEntity<>(managerToken), Map.class, kb);
        assertThat(notGranted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(output.getAll()).contains("event=access_denied user=" + manager + " knowledgeBase=" + kb);
    }

    @Test
    @DisplayName("拒答留下 answer_refused（带原因，不带问题原文）；文件有问题入库失败留下 document_failed")
    void logsRefusalsAndIngestionFailures(CapturedOutput output) {
        TestLogin.asAdmin(http);
        long kb = createKnowledgeBase("观测空库");

        var answer = TestAsk.ask(http, null, List.of(kb), "观测用的问题保险柜密码多少");
        assertThat(answer).containsEntry("refused", true);
        assertThat(output.getAll()).containsPattern("event=answer_refused answer=\\d+ user=\\d+ reason=[A-Z_]+")
                .doesNotContain("观测用的问题保险柜密码多少");

        var uploaded = upload(kb, "空白.txt", " \n\n  \n");
        assertThat(TestDocuments.awaitProcessed(http, kb, uploaded, null)).containsEntry("status", "FAILED");
        assertThat(output.getAll()).containsPattern(
                "event=document_failed document=" + uploaded.get("id") + " kind=content attempts=1");
    }

    @Test
    @DisplayName("低分改写留下 query_rewritten（带回答和改写结果），不带原问题和改写后的问题")
    void logsRewrites(CapturedOutput output) {
        // 本地确定性模型不会真的改写，这里直接记一条带改写结果的回答（同 AnswerReviewLoopTests）。
        var answer = AskResult.refused(RefusalReason.LOW_RELEVANCE, 0.01, "拒答").withRewrite(new Rewrite(
                Rewrite.Outcome.REWRITTEN, "观测用的改写问题临期商品打几折", 9, 800L, 100L, 200L));
        long id = answers.record(new TenantId(1), new UserId(1), "观测用的原问题临其商品打几着", answer).id();

        assertThat(output.getAll()).contains("event=query_rewritten answer=" + id + " outcome=REWRITTEN")
                .doesNotContain("观测用的原问题").doesNotContain("观测用的改写问题");
    }

    private long createStaff(String username) {
        return createUser(username, TestLogin.staffRoleId(http));
    }

    private long createUser(String username, long roleId) {
        var created = http.postForEntity("/api/users", Map.of("username", username, "displayName", "店员",
                "password", "staff-password", "roleIds", List.of(roleId)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> upload(long kb, String filename, String text) {
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
        return http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers), Map.class,
                kb).getBody();
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
