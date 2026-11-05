package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * 入库是异步的：上传接口先返回“解析中”，测试用这里的方法等到 READY 或 FAILED 再断言。
 */
final class TestDocuments {

    private static final ParameterizedTypeReference<Map<String, Object>> PAGE =
            new ParameterizedTypeReference<>() {
            };
    /** 评测用真实模型向量化，给足时间。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private TestDocuments() {
    }

    /** 等上传的文档处理完，返回它在文档列表里的最新状态。{@code token} 为 null 时沿用客户端默认的令牌。 */
    static Map<String, Object> awaitProcessed(TestRestTemplate http, long knowledgeBaseId,
            Map<String, Object> uploaded, String token) {
        assertThat(uploaded).as("上传响应").containsKey("id");
        var id = ((Number) uploaded.get("id")).longValue();
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var documents = all(http, knowledgeBaseId, headers);
            var current = documents.stream()
                    .filter(document -> ((Number) document.get("id")).longValue() == id)
                    .findFirst()
                    .orElseThrow();
            if (!"PARSING".equals(current.get("status"))) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail("文档 " + id + " 在 " + TIMEOUT.toSeconds() + " 秒内没有处理完");
    }

    /** 文档列表是分页的（D-039），每页 100 条翻到底。{@code headers} 为空时沿用客户端默认的令牌。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> all(TestRestTemplate http, long knowledgeBaseId, HttpHeaders headers) {
        var documents = new java.util.ArrayList<Map<String, Object>>();
        for (int page = 1; ; page++) {
            var response = http.exchange("/api/knowledge-bases/{id}/documents?page={page}&size=100", HttpMethod.GET,
                    new HttpEntity<>(headers), PAGE, knowledgeBaseId, page).getBody();
            var items = (List<Map<String, Object>>) response.get("items");
            documents.addAll(items);
            if (items.isEmpty() || documents.size() >= ((Number) response.get("total")).longValue()) {
                return documents;
            }
        }
    }
}
