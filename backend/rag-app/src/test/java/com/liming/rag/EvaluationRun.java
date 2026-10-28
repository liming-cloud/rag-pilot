package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.liming.rag.ai.AnswerJudge;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.evaluation.api.EvalCase;
import com.liming.rag.evaluation.api.EvalOutcome;
import com.liming.rag.evaluation.api.EvalReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
 *
 * <p>题目带参考答案时（开源数据集），回答再交给评判模型对照参考答案判断，配置见 {@link #judge()}。
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "MODEL_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.storage.root=${java.io.tmpdir}/rag-eval-files",
        "rag.ai.embedding.provider=${EVAL_EMBEDDING_PROVIDER:openai}",
        "rag.ai.rerank.provider=${EVAL_RERANK_PROVIDER:openai}",
        "rag.ai.chat.provider=${EVAL_CHAT_PROVIDER:openai}",
        "rag.answering.min-relevance=${EVAL_MIN_RELEVANCE:0.2}",
        "rag.answering.rewrite-on-low-relevance=${EVAL_REWRITE:true}",
        "rag.ingestion.concurrency=${EVAL_INGESTION_CONCURRENCY:4}"})
@AutoConfigureTestRestTemplate
@Testcontainers
class EvaluationRun {

    /** 评判默认用硅基流动上的免费模型，与回答模型不同系列；换成自己部署的专用评判模型时改 EVAL_JUDGE_BASE_URL 和 EVAL_JUDGE_MODEL。 */
    private static final String DEFAULT_JUDGE_MODEL = "Qwen/Qwen2.5-7B-Instruct";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> DOCUMENTS =
            new ParameterizedTypeReference<>() {
            };

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

        var cases = loadCases();
        var judge = cases.stream().anyMatch(evalCase -> evalCase.referenceAnswer() != null) ? judge() : null;
        var outcomes = new ArrayList<EvalOutcome>();
        for (var evalCase : cases) {
            outcomes.add(judged(judge, run(store, restricted, staff, evalCase), outcomes.isEmpty()));
            if (outcomes.size() % 50 == 0) {
                System.out.printf(Locale.ROOT, "已评测 %d / %d 题%n", outcomes.size(), cases.size());
            }
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
     * 评判模型：{@code EVAL_JUDGE_PROVIDER=none} 时不评判（离线试跑）；否则调用 OpenAI 兼容接口，
     * 地址 {@code EVAL_JUDGE_BASE_URL}（默认同 MODEL_BASE_URL），模型 {@code EVAL_JUDGE_MODEL}，
     * 密钥 {@code EVAL_JUDGE_API_KEY}（默认用 MODEL_API_KEY）。
     */
    private static AnswerJudge judge() {
        if ("none".equals(env("EVAL_JUDGE_PROVIDER", "openai"))) {
            return null;
        }
        return AnswerJudge.openAiCompatible(env("EVAL_JUDGE_BASE_URL", env("MODEL_BASE_URL", "https://api.siliconflow.cn/v1")),
                env("EVAL_JUDGE_API_KEY", System.getenv("MODEL_API_KEY")), env("EVAL_JUDGE_MODEL", DEFAULT_JUDGE_MODEL),
                Duration.ofSeconds(60));
    }

    /**
     * 有参考答案且给出了回答的题交给评判。第一题就失败多半是配置不对（模型名、地址），直接停下；
     * 之后偶发失败只记警告，这道题不计入评判。
     */
    private static EvalOutcome judged(AnswerJudge judge, EvalOutcome outcome, boolean first) {
        var evalCase = outcome.evalCase();
        if (judge == null || outcome.refused() || evalCase.referenceAnswer() == null) {
            return outcome;
        }
        try {
            var judgement = judge.judge(evalCase.question(), evalCase.referenceAnswer(), outcome.answer());
            return outcome.withJudgement(new EvalOutcome.Judgement(judgement.verdict().name(), judgement.reason()));
        } catch (ModelCallException e) {
            var cause = e.getCause() == null ? "" : "：" + e.getCause().getMessage();
            if (first) {
                throw new IllegalStateException(e.getMessage() + cause, e);
            }
            System.out.println("::warning title=答案评判失败::" + evalCase.id() + cause);
            return outcome;
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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
            // 合成集每题一篇原文（expectedDocument），开源数据集可能有多篇（expectedDocuments）。
            @SuppressWarnings("unchecked")
            var documents = item.get("expectedDocuments") instanceof List<?> list ? (List<String>) list
                    : item.get("expectedDocument") instanceof String one ? List.of(one) : List.<String>of();
            var evalCase = new EvalCase((String) item.get("id"), (String) item.get("question"),
                    EvalCase.Kind.valueOf(((String) item.get("kind")).toUpperCase(Locale.ROOT)),
                    documents, keywords, Boolean.TRUE.equals(item.get("restricted")),
                    (String) item.get("style"), (String) item.get("referenceAnswer"));
            // 从回答记录导出的草稿题要先人工标注，没标完就跑会让指标失真。
            if (evalCase.kind() == EvalCase.Kind.ANSWERABLE && (evalCase.expectedDocuments().isEmpty()
                    || evalCase.keywords().isEmpty() && evalCase.referenceAnswer() == null)) {
                throw new IllegalStateException("评测题“" + evalCase.id() + "”还没标注预期文档和答案关键词（或参考答案）");
            }
            return evalCase;
        }).toList();
    }

    /** 先把文件全部上传，再统一等入库完成：后台按并发数处理，比逐篇上传逐篇等快得多。 */
    private Set<String> uploadCorpus(long knowledgeBaseId, String folder) throws IOException {
        var names = new HashSet<String>();
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
            assertThat(uploaded.getStatusCode()).as(filename + " 上传").isEqualTo(HttpStatus.CREATED);
            names.add(filename);
        }
        if (!names.isEmpty()) {
            awaitAllProcessed(knowledgeBaseId, names.size());
        }
        // 私有评测集可以没有店长专用库；合成集两个库都有。
        if (folder.equals("store")) {
            assertThat(names).as(folder + " 语料").isNotEmpty();
        }
        return names;
    }

    /** 每篇给 3 秒、至少 90 秒，等全部文档离开“解析中”，并要求都是可用。 */
    private void awaitAllProcessed(long knowledgeBaseId, int count) {
        var timeout = Duration.ofSeconds(Math.max(90, 3L * count));
        long deadline = System.nanoTime() + timeout.toNanos();
        long started = System.nanoTime();
        while (System.nanoTime() < deadline) {
            var documents = http.exchange("/api/knowledge-bases/{id}/documents", HttpMethod.GET, null, DOCUMENTS,
                    knowledgeBaseId).getBody();
            if (documents.stream().noneMatch(document -> "PARSING".equals(document.get("status")))) {
                var failed = documents.stream().filter(document -> !"READY".equals(document.get("status")))
                        .map(document -> document.get("filename") + "：" + document.get("error")).toList();
                assertThat(failed).as("入库失败的文档").isEmpty();
                System.out.printf(Locale.ROOT, "%d 篇文档入库用时 %d 秒%n", documents.size(),
                        (System.nanoTime() - started) / 1_000_000_000);
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(count + " 篇文档在 " + timeout.toSeconds() + " 秒内没有全部处理完");
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
