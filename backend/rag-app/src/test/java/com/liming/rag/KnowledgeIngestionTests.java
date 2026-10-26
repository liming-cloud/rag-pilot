package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 通过 HTTP 走一遍文档入库：建知识库 → 上传 TXT → 分块入库，以及重复、格式不支持、编码错误三种失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rag.storage.root=${java.io.tmpdir}/rag-test-files")
@AutoConfigureTestRestTemplate
@Testcontainers
class KnowledgeIngestionTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("上传门店样例文本后分块入库，可在文档列表看到")
    void uploadsAndChunksText() throws IOException {
        long knowledgeBaseId = createKnowledgeBase("门店运营知识库");
        var sample = new ClassPathResource("fixtures/store-knowledge.txt").getContentAsByteArray();

        var uploaded = upload(knowledgeBaseId, "门店商品知识.txt", sample);

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploaded.getBody()).containsEntry("status", "READY");
        int chunkCount = ((Number) uploaded.getBody().get("chunkCount")).intValue();
        assertThat(chunkCount).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from chunk where knowledge_base_id = ? and tenant_id = 1",
                Integer.class, knowledgeBaseId)).isEqualTo(chunkCount);
        assertThat(jdbc.queryForObject("select count(*) from chunk where knowledge_base_id = ? and content like ?",
                Integer.class, knowledgeBaseId, "%青柚气泡水%")).isPositive();

        var listed = http.getForEntity("/api/knowledge-bases/{id}/documents", List.class, knowledgeBaseId);
        assertThat(listed.getBody()).hasSize(1);

        var duplicate = upload(knowledgeBaseId, "副本.txt", sample);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("不支持的格式拒绝；非 UTF-8 文本保存为失败文档并给出原因")
    void rejectsUnsupportedAndRecordsEncodingFailure() {
        long knowledgeBaseId = createKnowledgeBase("失败场景知识库");

        var pdf = upload(knowledgeBaseId, "收银规范.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        var gbk = upload(knowledgeBaseId, "旧编码.txt", new byte[] {(byte) 0xC1, (byte) 0xD9, (byte) 0xC6});
        assertThat(gbk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(gbk.getBody()).containsEntry("status", "FAILED");
        assertThat((String) gbk.getBody().get("error")).contains("UTF-8");
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> upload(long knowledgeBaseId, String filename, byte[] content) {
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
        ResponseEntity response = http.postForEntity("/api/knowledge-bases/{id}/documents",
                new HttpEntity<>(body, headers), Map.class, knowledgeBaseId);
        return response;
    }
}
