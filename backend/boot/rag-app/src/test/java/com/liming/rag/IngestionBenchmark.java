package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 大文档入库耗时（#200），默认不跑（标签 benchmark），手动运行：
 * {@code ./mvnw verify -pl backend/boot/rag-app -am -Dtest.excludedGroups=eval -Dtest=IngestionBenchmark
 * -Dsurefire.failIfNoSpecifiedTests=false}。上传 {@value #SECTIONS} 节的 Markdown（每节一块），从上传到处理完计时，
 * 先热身一次再测 {@value #TIMES} 次，结果打印在输出里（中位数，毫秒）。只用来比较改动前后，数字随机器变化，不作断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Tag("benchmark")
class IngestionBenchmark {

    private static final int SECTIONS = 3000;
    private static final int TIMES = 3;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void measure() {
        TestLogin.asAdmin(http);
        long knowledgeBaseId = ((Number) http.postForObject("/api/knowledge-bases", Map.of("name", "入库基准"),
                Map.class).get("id")).longValue();
        ingest(knowledgeBaseId, -1);
        var millis = new long[TIMES];
        long chunks = 0;
        for (int run = 0; run < TIMES; run++) {
            long started = System.nanoTime();
            long documentId = ingest(knowledgeBaseId, run);
            millis[run] = (System.nanoTime() - started) / 1_000_000;
            chunks = jdbc.queryForObject("SELECT count(*) FROM chunk WHERE document_id = ?", Long.class, documentId);
        }
        Arrays.sort(millis);
        System.out.printf("benchmark case=ingest sections=%d chunks=%d n=%d p50=%dms min=%dms max=%dms%n", SECTIONS,
                chunks, TIMES, millis[TIMES / 2], millis[0], millis[TIMES - 1]);
    }

    /** 上传一份大文档并等它处理完；{@code run} 让每次内容不同，避免按重复文件拒绝。 */
    @SuppressWarnings("unchecked")
    private long ingest(long knowledgeBaseId, int run) {
        var text = new StringBuilder();
        for (int i = 0; i < SECTIONS; i++) {
            text.append("## 第 ").append(i).append(" 节（").append(run).append("）\n\n")
                    .append("门店第 ").append(i).append(" 条规范：收银员交班时要清点备用金，冷柜温度保持在 2 到 6 度，")
                    .append("临期商品提前三天下架并登记，会员积分按实付金额计算。\n\n");
        }
        var file = new ByteArrayResource(text.toString().getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "大文档-" + run + ".md";
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> uploaded = http.postForObject("/api/knowledge-bases/{id}/documents",
                new HttpEntity<>(body, headers), Map.class, knowledgeBaseId);
        var processed = TestDocuments.awaitProcessed(http, knowledgeBaseId, uploaded, null);
        assertThat(processed).containsEntry("status", "READY");
        return ((Number) processed.get("id")).longValue();
    }
}
