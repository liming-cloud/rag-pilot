package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.knowledge.application.ingestion.Rechunker;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 识别图片里的文字（#223）：识别服务接到本地假的 RapidOCR。截图里的文字随文档入库；服务不可用时照常入库、标为待识别，
 * 服务恢复后由重新分块补上，期间不重做。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ImageTextTests {

    private static final String RECOGNIZED = """
            {"0":{"rec_txt":"交班前在收银台点击“交班”按钮","dt_boxes":[[0,0],[90,0],[90,20],[0,20]],"score":0.99}}
            """;
    private static volatile int status = 200;
    private static final HttpServer OCR = start();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Rechunker rechunker;

    @DynamicPropertySource
    static void ocr(DynamicPropertyRegistry registry) {
        registry.add("rag.knowledge.ocr.url", () -> "http://127.0.0.1:" + OCR.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        OCR.stop(0);
    }

    @BeforeEach
    void signIn() {
        status = 200;
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("截图里的文字跟在所在段落后面入库，能被检索到")
    void storesTextInScreenshots() throws IOException {
        long knowledgeBaseId = createKnowledgeBase("截图手册知识库");

        long documentId = uploadAndAwait(knowledgeBaseId, "交班手册.docx");

        assertThat(contents(documentId)).anySatisfy(content -> assertThat(content)
                .contains("按下图操作。\n图片文字：交班前在收银台点击“交班”按钮"));
        assertThat(pending(documentId)).isFalse();
    }

    @Test
    @DisplayName("识别服务不可用：照常入库、标为待识别；仍不可用时重新分块不重做，恢复后补上")
    void catchesUpAfterOutage() throws IOException {
        long knowledgeBaseId = createKnowledgeBase("识别中断知识库");
        status = 503;

        long documentId = uploadAndAwait(knowledgeBaseId, "收银手册.docx");

        assertThat(contents(documentId)).noneSatisfy(content -> assertThat(content).contains("图片文字"));
        assertThat(pending(documentId)).isTrue();
        var before = jdbc.queryForList("SELECT id FROM chunk WHERE document_id = ?", Long.class, documentId);

        assertThat(rechunker.rechunkAll()).as("服务仍不可用，这一轮停下").isZero();
        assertThat(jdbc.queryForList("SELECT id FROM chunk WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(before);

        status = 200;
        assertThat(rechunker.rechunkAll()).isEqualTo(1);
        assertThat(contents(documentId)).anySatisfy(content -> assertThat(content).contains("图片文字：交班前"));
        assertThat(pending(documentId)).isFalse();
    }

    private List<String> contents(long documentId) {
        return jdbc.queryForList("SELECT content FROM chunk WHERE document_id = ? ORDER BY ordinal", String.class,
                documentId);
    }

    private boolean pending(long documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT image_text_pending FROM document WHERE id = ?",
                Boolean.class, documentId));
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ocr", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var body = (status == 200 ? RECOGNIZED : "busy").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一句话和一张截图（每个测试用不同的尺寸，免得文件内容相同被当成重复上传）。 */
    private static byte[] manual(int size) throws IOException {
        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", image);
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("按下图操作。");
            try {
                document.createParagraph().createRun().addPicture(new ByteArrayInputStream(image.toByteArray()),
                        PictureType.PNG, "step.png", 9525, 9525);
            } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
                throw new IOException(e);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private long createKnowledgeBase(String name) {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", name), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private long uploadAndAwait(long knowledgeBaseId, String filename) throws IOException {
        var content = manual(filename.length() * 10);
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
        var uploaded = http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers),
                Map.class, knowledgeBaseId);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var processed = TestDocuments.awaitProcessed(http, knowledgeBaseId, uploaded.getBody(), null);
        assertThat(processed).containsEntry("status", "READY");
        return ((Number) processed.get("id")).longValue();
    }
}
