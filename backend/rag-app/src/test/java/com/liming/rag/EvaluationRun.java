package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.evaluation.api.EvalCase;
import com.liming.rag.evaluation.api.EvalOutcome;
import com.liming.rag.evaluation.api.EvalReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用真实模型跑完整评测：上传 test 资源里的 eval 语料 → 逐题检索与问答 → 计算指标并写报告。
 *
 * <p>默认构建不运行（标签 eval），需要环境变量 MODEL_API_KEY。报告写到 target/eval-report.md，
 * 在 GitHub Actions 里同时写进运行摘要。越权泄漏必须为 0，其余指标只报告。
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "MODEL_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.storage.root=${java.io.tmpdir}/rag-eval-files",
        "rag.ai.embedding.provider=${EVAL_EMBEDDING_PROVIDER:openai}",
        "rag.ai.rerank.provider=${EVAL_RERANK_PROVIDER:openai}",
        "rag.ai.chat.provider=${EVAL_CHAT_PROVIDER:openai}",
        "rag.answering.min-relevance=${EVAL_MIN_RELEVANCE:0.2}",
        "rag.answering.rewrite-on-low-relevance=${EVAL_REWRITE:true}"})
@AutoConfigureTestRestTemplate
@Testcontainers
class EvaluationRun {

    private static final PathMatchingResourcePatternResolver RESOURCES = new PathMatchingResourcePatternResolver();

    private static final ParameterizedTypeReference<List<Map<String, Object>>> HITS =
            new ParameterizedTypeReference<>() {
            };

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
    @DisplayName("评测集：召回、引用、拒答、越权、延迟")
    void evaluate() throws IOException {
        long store = createKnowledgeBase("鲜邻便利门店运营");
        long restricted = createKnowledgeBase("鲜邻便利店长专用");
        uploadCorpus(store, "store");
        var restrictedDocuments = uploadCorpus(restricted, "restricted");

        // 以只被授权门店库的店员身份提问，并在请求里主动带上店长专用库，检验越权是否真的被挡住。
        String staff = createStaffWithAccessTo(store);

        var outcomes = new ArrayList<EvalOutcome>();
        for (var evalCase : loadCases()) {
            outcomes.add(run(store, restricted, staff, evalCase));
        }
        var report = EvalReport.of(outcomes, restrictedDocuments);
        publish(report);

        assertThat(report.leaks()).as("越权泄漏必须为 0").isZero();
    }

    private EvalOutcome run(long knowledgeBaseId, long restricted, String staff, EvalCase evalCase) {
        long start = System.nanoTime();
        var hits = http.exchange("/api/knowledge-bases/{id}/search?q={q}&limit=10", HttpMethod.GET, null, HITS,
                knowledgeBaseId, evalCase.question()).getBody();
        long searchMillis = (System.nanoTime() - start) / 1_000_000;

        var events = SseEvents.post(http, "/api/ask/stream",
                Map.of("question", evalCase.question(), "knowledgeBaseIds", List.of(knowledgeBaseId, restricted)), staff);
        assertThat(events).as(evalCase.id() + " 流式问答").isNotEmpty();
        var done = events.getLast();
        assertThat(done.name()).as(evalCase.id() + " 流式问答结果：" + done.data()).isEqualTo("done");
        Map<String, Object> answer = JsonMapper.builder().build().readValue(done.data(), new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        var citations = (List<Map<String, Object>>) answer.get("citations");

        return new EvalOutcome(evalCase,
                hits.stream().map(hit -> new EvalOutcome.Retrieved((String) hit.get("documentName"),
                        (String) hit.get("content"))).toList(),
                Boolean.TRUE.equals(answer.get("refused")),
                (String) answer.get("refusalReason"),
                (String) answer.get("text"),
                citations.stream().map(citation -> (String) citation.get("documentName")).toList(),
                answer.get("topRelevance") == null ? null : ((Number) answer.get("topRelevance")).doubleValue(),
                searchMillis, events.getFirst().atMillis(), done.atMillis(), stages(answer), rewrite(answer));
    }

    /**
     * 评测数据的位置：设置了 {@code EVAL_DATA_DIR} 时用仓库外的私有评测集（客户真实资料和问题），
     * 否则用仓库里的合成集。目录结构相同：{@code corpus/store/}、{@code corpus/restricted/}（可选）、{@code cases.json}。
     */
    private static String dataLocation() {
        var dir = System.getenv("EVAL_DATA_DIR");
        if (dir == null || dir.isBlank()) {
            return "classpath:eval/";
        }
        var uri = Path.of(dir).toAbsolutePath().toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private static List<EvalCase> loadCases() throws IOException {
        var json = RESOURCES.getResource(dataLocation() + "cases.json").getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> raw = JsonMapper.builder().build().readValue(json, new TypeReference<>() {
        });
        return raw.stream().map(item -> {
            @SuppressWarnings("unchecked")
            var keywords = (List<String>) item.get("keywords");
            var evalCase = new EvalCase((String) item.get("id"), (String) item.get("question"),
                    EvalCase.Kind.valueOf(((String) item.get("kind")).toUpperCase(Locale.ROOT)),
                    (String) item.get("expectedDocument"), keywords, Boolean.TRUE.equals(item.get("restricted")),
                    (String) item.get("style"));
            // 从回答记录导出的草稿题要先人工标注，没标完就跑会让指标失真。
            if (evalCase.kind() == EvalCase.Kind.ANSWERABLE
                    && (evalCase.expectedDocument() == null || evalCase.keywords().isEmpty())) {
                throw new IllegalStateException("评测题“" + evalCase.id() + "”还没标注预期文档和答案关键词");
            }
            return evalCase;
        }).toList();
    }

    private Set<String> uploadCorpus(long knowledgeBaseId, String folder) throws IOException {
        var names = new java.util.HashSet<String>();
        for (var resource : RESOURCES.getResources(dataLocation() + "corpus/" + folder + "/*")) {
            var filename = resource.getFilename();
            if (filename == null || filename.startsWith(".") || !resource.isReadable()) {
                continue;
            }
            var file = new ByteArrayResource(resource.getContentAsByteArray()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            var body = new LinkedMultiValueMap<String, Object>();
            body.add("file", file);
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            var uploaded = http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers),
                    Map.class, knowledgeBaseId);
            assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, uploaded.getBody(), null)).as(filename + " 入库")
                    .containsEntry("status", "READY");
            names.add(filename);
        }
        // 私有评测集可以没有店长专用库；合成集两个库都有。
        if (folder.equals("store")) {
            assertThat(names).as(folder + " 语料").isNotEmpty();
        }
        return names;
    }

    private String createStaffWithAccessTo(long knowledgeBaseId) {
        var created = http.postForEntity("/api/users", Map.of("username", "eval-staff", "displayName", "评测店员",
                "password", "eval-staff-password", "role", "STAFF"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long userId = ((Number) created.getBody().get("id")).longValue();
        http.put("/api/knowledge-bases/{id}/members/{userId}", null, knowledgeBaseId, userId);
        return TestLogin.token(http, "eval-staff", "eval-staff-password");
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static EvalOutcome.Stages stages(Map<String, Object> answer) {
        if (!(answer.get("timings") instanceof Map<?, ?> timings)) {
            return null;
        }
        var values = (Map<String, Object>) timings;
        return new EvalOutcome.Stages(millis(values.get("retrievalMillis")), millis(values.get("rerankMillis")),
                millis(values.get("firstTokenMillis")), millis(values.get("rewriteMillis")));
    }

    /** 没有对应题目时比例是 NaN，显示成“-”。 */
    private static String percent(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    @SuppressWarnings("unchecked")
    private static EvalOutcome.Rewrite rewrite(Map<String, Object> answer) {
        if (!(answer.get("rewrite") instanceof Map<?, ?> rewrite)) {
            return null;
        }
        var values = (Map<String, Object>) rewrite;
        return new EvalOutcome.Rewrite((String) values.get("outcome"), (String) values.get("question"),
                ((Number) values.get("outputChars")).intValue(), millis(values.get("modelMillis")),
                millis(values.get("retrievalMillis")), millis(values.get("rerankMillis")));
    }

    private static Long millis(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static void publish(EvalReport report) throws IOException {
        var markdown = report.toMarkdown();
        Files.writeString(Path.of("target", "eval-report.md"), markdown);
        var summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            Files.writeString(Path.of(summary), markdown, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.printf(Locale.ROOT,
                "::notice title=评测结果::召回 %s，引用正确 %s，拒答正确 %s，误拒 %s，越权 %d，检索 p95 %d ms，首字 p95 %d ms，整段 p95 %d ms%n",
                percent(report.recallAt10()), percent(report.citationAccuracy()), percent(report.refusalAccuracy()),
                percent(report.falseRefusalRate()), report.leaks(), report.searchP95Millis(), report.firstTokenP95Millis(),
                report.askP95Millis());
        // 失败题和分数逐条写成注释，不下载报告也能在 PR 检查页看到。
        report.failures().forEach(failure -> System.out.println("::warning title=评测未通过::" + failure));
        // GitHub 每步每类注释最多显示 10 条，逐题分数合成一条；%0A 是注释里的换行。
        var detail = new StringBuilder();
        for (var outcome : report.outcomes()) {
            detail.append(String.format(Locale.ROOT, "%s（%s）最高分 %s，%s%%0A", outcome.evalCase().id(),
                    outcome.evalCase().kind() == EvalCase.Kind.ANSWERABLE ? "可回答" : "应拒答",
                    outcome.topRelevance() == null ? "-" : String.format(Locale.ROOT, "%.3f", outcome.topRelevance()),
                    outcome.refused() ? "拒答 " + outcome.refusalReason() : "已回答"));
        }
        System.out.println("::notice title=评测逐题分数::" + detail);
    }
}
