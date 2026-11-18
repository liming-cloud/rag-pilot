package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 知识质量（#224、D-070）端到端：题集跨库；查看人、运行人无权访问的库里的题当作不存在（经角色授权能看到，收回后看不到、
 * 也跑不到）；期望文档上传新版本后接到新版本，删除后题目过期、不参与运行；运行在后台逐题跑完，和上一次对比；
 * 试问和运行都不写回答记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class EvaluationTests {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String EXPIRY = "临期商品在到期前 3 天打七折，到期前 1 天打五折，过期当天下架报损。";
    private static final String DISPLAY = "饮料陈列在一层 A 区 03 号货架第二层，补货时先移出临期批次。";
    private static final String BONUS = "店长季度奖金按门店毛利达成率计算，达成 100% 发放基数的 1.2 倍。";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("经角色授权能看到、运行库里的题；收回授权后这些题不显示、不运行、不计分")
    void questionsInInaccessibleKnowledgeBasesDoNotExist() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long store = createKnowledgeBase(admin, "评测门店库");
        long manager = createKnowledgeBase(admin, "评测店长库");
        long expiry = upload(admin, store, "临期管理.txt", EXPIRY);
        long bonus = upload(admin, manager, "店长奖金.txt", BONUS);
        long set = createSet(admin, "门店回归");
        addQuestion(admin, set, "临期商品打几折", store, "DOCUMENTS", List.of(expiry));
        addQuestion(admin, set, "店长季度奖金怎么算", manager, "DOCUMENTS", List.of(bonus));

        long role = createRole(admin, "评测店长", List.of("eval:view", "eval:run"));
        createUser(admin, "eval01", role);
        String runner = TestLogin.token(http, "eval01", "staff-password");
        assertThat(send(HttpMethod.PUT, "/api/knowledge-bases/" + store + "/role-grants/" + role, admin, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(list(runner, "/api/evaluation/sets/" + set + "/questions"))
                .extracting(question -> question.get("question")).containsExactly("临期商品打几折");
        assertThat(map(runner, "/api/evaluation/sets/" + set)).containsEntry("questionCount", 1)
                .containsEntry("knowledgeBaseNames", List.of("评测门店库"));
        var run = awaitDone(runner, start(runner, set));
        assertThat(run).containsEntry("total", 1).containsEntry("done", 1);
        assertThat(results(run)).extracting(result -> result.get("question")).containsExactly("临期商品打几折");
        // 管理员能访问两个库，看得到同一次运行里的全部题；这次运行只跑了运行人能访问的那道。
        assertThat(map(admin, "/api/evaluation/runs/" + run.get("id"))).containsEntry("total", 1);

        assertThat(send(HttpMethod.DELETE, "/api/knowledge-bases/" + store + "/role-grants/" + role, admin, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(runner, "/api/evaluation/sets/" + set + "/questions")).isEmpty();
        assertThat(map(runner, "/api/evaluation/sets/" + set)).containsEntry("questionCount", 0);
        var hidden = map(runner, "/api/evaluation/runs/" + run.get("id"));
        assertThat(hidden).containsEntry("total", 0).containsEntry("passed", 0);
        assertThat(results(hidden)).isEmpty();
        assertThat(send(HttpMethod.POST, "/api/evaluation/sets/" + set + "/runs", runner, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, "/api/evaluation/trial", runner,
                Map.of("knowledgeBaseId", store, "question", "临期商品打几折")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("运行在后台跑完：期望文档和应当拒答各自判定，第二次运行和第一次对比；试问和运行不写回答记录")
    void runsJudgeAndCompareWithoutRecordingAnswers() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long store = createKnowledgeBase(admin, "评测运行库");
        long expiry = upload(admin, store, "临期规定.txt", EXPIRY);
        long set = createSet(admin, "运行回归");
        addQuestion(admin, set, "临期商品到期前 3 天打几折", store, "DOCUMENTS", List.of(expiry));
        addQuestion(admin, set, "公司股票什么时候分红派息", store, "REFUSAL", List.of());
        long recordsBefore = answerRecords();

        var trial = send(HttpMethod.POST, "/api/evaluation/trial", admin,
                Map.of("knowledgeBaseId", store, "question", "临期商品到期前 3 天打几折"));
        assertThat(trial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trial.getBody()).containsEntry("refused", false);
        assertThat((List<?>) trial.getBody().get("citedDocuments")).hasSize(1);

        var first = awaitDone(admin, start(admin, set));
        assertThat(first).containsEntry("total", 2).containsEntry("passed", 2).containsEntry("failed", 0);
        assertThat(results(first)).extracting(result -> result.get("change")).containsOnly("NEW");
        var second = awaitDone(admin, start(admin, set));
        assertThat(results(second)).extracting(result -> result.get("change")).containsOnly("SAME");
        assertThat(list(admin, "/api/evaluation/sets/" + set + "/runs")).extracting(run -> run.get("id"))
                .containsExactly(second.get("id"), first.get("id"));
        var csv = http.exchange("/api/evaluation/runs/" + second.get("id") + "/export", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), byte[].class);
        assertThat(new String(csv.getBody(), StandardCharsets.UTF_8)).startsWith("\uFEFF问题,知识库").contains("通过");

        assertThat(answerRecords()).as("试问和运行不写回答记录").isEqualTo(recordsBefore);
    }

    @Test
    @DisplayName("期望文档上传新版本后接到新版本；删除后题目过期，运行前预估里跳过，运行时不跑")
    void expectedDocumentsFollowNewVersionsAndGoStaleWhenDeleted() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long store = createKnowledgeBase(admin, "评测版本库");
        long expiry = upload(admin, store, "临期办法.txt", EXPIRY);
        long display = upload(admin, store, "陈列办法.txt", DISPLAY);
        long set = createSet(admin, "版本回归");
        long followed = addQuestion(admin, set, "临期商品打几折", store, "DOCUMENTS", List.of(expiry));
        long stale = addQuestion(admin, set, "饮料陈列在哪个货架", store, "DOCUMENTS", List.of(display));

        long newVersion = uploadVersion(admin, store, expiry, "临期办法.txt", EXPIRY + "新版本。");
        assertThat(send(HttpMethod.DELETE, "/api/knowledge-bases/" + store + "/documents/" + display, admin, null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var questions = list(admin, "/api/evaluation/sets/" + set + "/questions");
        var first = question(questions, followed);
        assertThat(first).containsEntry("stale", false);
        assertThat(expected(first)).singleElement()
                .satisfies(document -> assertThat(((Number) document.get("documentId")).longValue()).isEqualTo(newVersion));
        assertThat(question(questions, stale)).containsEntry("stale", true);
        assertThat(map(admin, "/api/evaluation/sets/" + set)).containsEntry("staleCount", 1);
        assertThat(map(admin, "/api/evaluation/sets/" + set + "/estimate")).containsEntry("questions", 1)
                .containsEntry("skippedStale", 1);
        var run = awaitDone(admin, start(admin, set));
        assertThat(results(run)).extracting(result -> ((Number) result.get("questionId")).longValue())
                .containsExactly(followed);
    }

    // ---- 工具 ----

    private long answerRecords() {
        return jdbc.queryForObject("SELECT count(*) FROM answer_record", Long.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> expected(Map<String, Object> question) {
        return (List<Map<String, Object>>) question.get("expectedDocuments");
    }

    private static Map<String, Object> question(List<Map<String, Object>> questions, long id) {
        return questions.stream().filter(question -> ((Number) question.get("id")).longValue() == id).findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> run) {
        return (List<Map<String, Object>>) run.get("results");
    }

    private long start(String token, long set) {
        var started = send(HttpMethod.POST, "/api/evaluation/sets/" + set + "/runs", token, null);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) started.getBody().get("id")).longValue();
    }

    private Map<String, Object> awaitDone(String token, long run) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var detail = map(token, "/api/evaluation/runs/" + run);
            if (!"RUNNING".equals(detail.get("status"))) {
                assertThat(detail).containsEntry("status", "DONE");
                return detail;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail("运行 " + run + " 在 " + TIMEOUT.toSeconds() + " 秒内没有跑完");
    }

    private long createSet(String token, String name) {
        var created = send(HttpMethod.POST, "/api/evaluation/sets", token, Map.of("name", name, "description", ""));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private long addQuestion(String token, long set, String question, long knowledgeBase, String expectation,
            List<Long> documents) {
        var created = send(HttpMethod.POST, "/api/evaluation/sets/" + set + "/questions", token, Map.of(
                "question", question, "knowledgeBaseId", knowledgeBase, "expectation", expectation, "match", "ALL",
                "expectedDocumentIds", documents));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private long createRole(String admin, String name, List<String> permissions) {
        var created = send(HttpMethod.POST, "/api/roles", admin, Map.of("name", name, "permissions", permissions));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private void createUser(String admin, String username, long role) {
        var created = send(HttpMethod.POST, "/api/users", admin, Map.of("username", username, "displayName", "评测员",
                "password", "staff-password", "roleIds", List.of(role)));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long createKnowledgeBase(String token, String name) {
        var created = send(HttpMethod.POST, "/api/knowledge-bases", token, Map.of("name", name));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private long upload(String token, long knowledgeBase, String filename, String text) {
        return processed(token, knowledgeBase, "/api/knowledge-bases/" + knowledgeBase + "/documents", filename, text);
    }

    private long uploadVersion(String token, long knowledgeBase, long document, String filename, String text) {
        long id = processed(token, knowledgeBase,
                "/api/knowledge-bases/" + knowledgeBase + "/documents/" + document + "/versions", filename, text);
        // 新版本就绪后旧版本被删除。
        assertThat(TestDocuments.all(http, knowledgeBase, auth(token)))
                .noneMatch(row -> ((Number) row.get("id")).longValue() == document);
        return id;
    }

    @SuppressWarnings("unchecked")
    private long processed(String token, long knowledgeBase, String path, String filename, String text) {
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
        var response = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        var document = TestDocuments.awaitProcessed(http, knowledgeBase, response.getBody(), token);
        assertThat(document).containsEntry("status", "READY");
        return ((Number) document.get("id")).longValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String path, String token, Object body) {
        return (ResponseEntity) http.exchange(path, method, new HttpEntity<>(body, auth(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String token, String path) {
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<Map<String, Object>> list(String token, String path) {
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), LIST);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
