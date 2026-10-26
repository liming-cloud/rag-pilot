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
import org.springframework.core.io.ClassPathResource;
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
        "rag.ai.embedding.provider=openai",
        "rag.ai.rerank.provider=openai",
        "rag.ai.chat.provider=openai",
        "rag.answering.min-relevance=${EVAL_MIN_RELEVANCE:0.2}"})
@AutoConfigureTestRestTemplate
@Testcontainers
class EvaluationRun {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> HITS =
            new ParameterizedTypeReference<>() {
            };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("评测集：召回、引用、拒答、越权、延迟")
    void evaluate() throws IOException {
        long store = createKnowledgeBase("鲜邻便利门店运营");
        long restricted = createKnowledgeBase("鲜邻便利店长专用");
        uploadCorpus(store, "store");
        var restrictedDocuments = uploadCorpus(restricted, "restricted");

        var outcomes = new ArrayList<EvalOutcome>();
        for (var evalCase : loadCases()) {
            outcomes.add(run(store, evalCase));
        }
        var report = EvalReport.of(outcomes, restrictedDocuments);
        publish(report);

        assertThat(report.leaks()).as("越权泄漏必须为 0").isZero();
    }

    private EvalOutcome run(long knowledgeBaseId, EvalCase evalCase) {
        long start = System.nanoTime();
        var hits = http.exchange("/api/knowledge-bases/{id}/search?q={q}&limit=10", HttpMethod.GET, null, HITS,
                knowledgeBaseId, evalCase.question()).getBody();
        long searchMillis = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        var response = http.postForEntity("/api/ask",
                Map.of("question", evalCase.question(), "knowledgeBaseIds", List.of(knowledgeBaseId)), Map.class);
        long askMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(response.getStatusCode()).as(evalCase.id() + " 问答接口").isEqualTo(HttpStatus.OK);
        var answer = response.getBody();
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
                searchMillis, askMillis);
    }

    private static List<EvalCase> loadCases() throws IOException {
        var json = new ClassPathResource("eval/cases.json").getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> raw = JsonMapper.builder().build().readValue(json, new TypeReference<>() {
        });
        return raw.stream().map(item -> {
            @SuppressWarnings("unchecked")
            var keywords = (List<String>) item.get("keywords");
            return new EvalCase((String) item.get("id"), (String) item.get("question"),
                    EvalCase.Kind.valueOf(((String) item.get("kind")).toUpperCase(Locale.ROOT)),
                    (String) item.get("expectedDocument"), keywords, Boolean.TRUE.equals(item.get("restricted")));
        }).toList();
    }

    private Set<String> uploadCorpus(long knowledgeBaseId, String folder) throws IOException {
        var names = new java.util.HashSet<String>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:eval/corpus/" + folder + "/*.md")) {
            var filename = resource.getFilename();
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
            assertThat(uploaded.getBody()).as(filename + " 入库").containsEntry("status", "READY");
            names.add(filename);
        }
        assertThat(names).as(folder + " 语料").isNotEmpty();
        return names;
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        return ((Number) created.getBody().get("id")).longValue();
    }

    private static void publish(EvalReport report) throws IOException {
        var markdown = report.toMarkdown();
        Files.writeString(Path.of("target", "eval-report.md"), markdown);
        var summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            Files.writeString(Path.of(summary), markdown, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.printf(Locale.ROOT,
                "::notice title=评测结果::召回 %.1f%%，引用正确 %.1f%%，拒答正确 %.1f%%，误拒 %.1f%%，越权 %d，检索 p95 %d ms，问答 p95 %d ms%n",
                report.recallAt10() * 100, report.citationAccuracy() * 100, report.refusalAccuracy() * 100,
                report.falseRefusalRate() * 100, report.leaks(), report.searchP95Millis(), report.askP95Millis());
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
