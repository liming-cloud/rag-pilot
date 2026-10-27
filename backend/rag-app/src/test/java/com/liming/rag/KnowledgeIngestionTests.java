package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.knowledge.application.IngestionWorker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IngestionWorker ingestionWorker;

    @Test
    @DisplayName("上传门店样例文本后分块入库，可在文档列表看到")
    void uploadsAndChunksText() throws IOException {
        long knowledgeBaseId = createKnowledgeBase("门店运营知识库");
        var sample = new ClassPathResource("fixtures/store-knowledge.txt").getContentAsByteArray();

        var uploaded = upload(knowledgeBaseId, "门店商品知识.txt", sample);

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploaded.getBody()).as("上传先返回，后台再解析").containsEntry("status", "PARSING");
        var processed = TestDocuments.awaitProcessed(http, knowledgeBaseId, uploaded.getBody(), null);
        assertThat(processed).containsEntry("status", "READY");
        int chunkCount = ((Number) processed.get("chunkCount")).intValue();
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
    @DisplayName("不支持的格式拒绝；非 UTF-8 文本、损坏的 PDF 保存为失败文档并给出原因")
    void rejectsUnsupportedAndRecordsEncodingFailure() {
        long knowledgeBaseId = createKnowledgeBase("失败场景知识库");

        var pptx = upload(knowledgeBaseId, "门店培训.pptx", "PK".getBytes(StandardCharsets.US_ASCII));
        assertThat(pptx.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        var brokenPdf = upload(knowledgeBaseId, "收银规范.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        var brokenPdfResult = TestDocuments.awaitProcessed(http, knowledgeBaseId, brokenPdf.getBody(), null);
        assertThat(brokenPdfResult).containsEntry("status", "FAILED");
        assertThat((String) brokenPdfResult.get("error")).contains("损坏");

        var gbk = upload(knowledgeBaseId, "旧编码.txt", new byte[] {(byte) 0xC1, (byte) 0xD9, (byte) 0xC6});
        assertThat(gbk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var gbkResult = TestDocuments.awaitProcessed(http, knowledgeBaseId, gbk.getBody(), null);
        assertThat(gbkResult).containsEntry("status", "FAILED");
        assertThat((String) gbkResult.get("error")).contains("UTF-8");
    }

    @Test
    @DisplayName("上传 Word 文档：按标题样式分章节入库，表格行带着表头")
    void uploadsWord() throws IOException {
        long knowledgeBaseId = createKnowledgeBase("Word 知识库");
        byte[] docx;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("商品陈列");
            document.createParagraph().createRun().setText("饮料按品牌集中陈列，新品放在视线高度。");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("商品");
            table.getRow(0).getCell(1).setText("货架");
            table.getRow(1).getCell(0).setText("青柚气泡水");
            table.getRow(1).getCell(1).setText("A-03");
            document.write(out);
            docx = out.toByteArray();
        }

        var uploaded = upload(knowledgeBaseId, "陈列规范.docx", docx);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, uploaded.getBody(), null))
                .containsEntry("status", "READY");
        assertThat(jdbc.queryForList("select heading_path || ' | ' || content from chunk where knowledge_base_id = ?",
                String.class, knowledgeBaseId))
                .anySatisfy(chunk -> assertThat(chunk).contains("商品陈列").contains("商品：青柚气泡水；货架：A-03"));
    }

    @Test
    @DisplayName("重启后，卡在“解析中”的文档会被重新处理")
    void resumesPendingDocumentsAfterRestart() {
        long knowledgeBaseId = createKnowledgeBase("恢复知识库");
        var uploaded = upload(knowledgeBaseId, "交班.txt", "交班时清点备用金，在交班表上签字。".getBytes(StandardCharsets.UTF_8));
        long id = ((Number) TestDocuments.awaitProcessed(http, knowledgeBaseId, uploaded.getBody(), null).get("id"))
                .longValue();
        // 模拟处理到一半时应用重启：分块没写入，状态停在解析中。
        jdbc.update("delete from chunk where document_id = ?", id);
        jdbc.update("update document set status = 'PARSING', chunk_count = 0 where id = ?", id);

        ingestionWorker.resumePending();

        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, Map.of("id", id), null))
                .containsEntry("status", "READY").containsEntry("chunkCount", 1);
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
