package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
import org.springframework.util.LinkedMultiValueMap;

/**
 * 资料体检（#218）：上传合成的文件，入库后文档列表给出结论；正常的文档不被误标，重复只列查看人能访问的库。
 * 各规则的阈值边界见 {@link ParseCheckSqlTests}（直接写信号，校验页面与 parse-check.sql 一致）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DocumentHealthTests {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("截图为主、没用标题样式、PDF 读不出章节、文档没有标题、表格过大被标出；正常的 Word、Markdown 标为正常")
    void flagsDocumentsByStoredSignals() throws IOException {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long kb = createKnowledgeBase(admin, "体检测试库");

        assertThat(codes(upload(admin, kb, "收银截图手册.docx", wordWithPictures(6)))).containsExactly("IMAGE_HEAVY");
        assertThat(codes(upload(admin, kb, "陈列规范.docx", word(true)))).isEmpty();
        assertThat(codes(upload(admin, kb, "陈列规范（无样式）.docx", word(false)))).containsExactly("WORD_NO_HEADINGS");
        assertThat(codes(upload(admin, kb, "handbook.pdf", longPdf()))).containsExactly("PDF_NO_STRUCTURE");
        assertThat(codes(upload(admin, kb, "盘点说明.md", text("盘点", false)))).containsExactly("NO_HEADINGS");
        assertThat(codes(upload(admin, kb, "盘点说明（分章）.md", text("盘点", true)))).isEmpty();
        assertThat(codes(upload(admin, kb, "全年排期.xlsx", excel(600)))).containsExactly("LARGE_TABLE");
        assertThat(codes(upload(admin, kb, "小排期.xlsx", excel(20)))).isEmpty();

        var needsWork = page(admin, "/api/knowledge-bases/" + kb + "/documents?health=needs_work");
        assertThat(needsWork).containsEntry("total", 5);
        assertThat(page(admin, "/api/knowledge-bases/" + kb + "/documents?health=OK")).containsEntry("total", 3);
        assertThat(summary(admin, "体检测试库")).containsEntry("needsWorkCount", 5);
    }

    @Test
    @DisplayName("重复、疑似重复在同一租户内跨库比较；只列查看人能访问的库，看不到的库里的副本不提示（越权）")
    void flagsDuplicatesOnlyInVisibleKnowledgeBases() {
        String admin = TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD);
        long store = createKnowledgeBase(admin, "体检重复门店库");
        long secret = createKnowledgeBase(admin, "体检重复机密库");
        var original = text("报损", true);
        upload(admin, store, "报损流程.md", original);
        upload(admin, secret, "报损流程（机密副本）.md", original);
        var draft = upload(admin, store, "报损流程（草稿）.md", original + "\n\n# 附录\n草稿另加的一句说明。");

        var listed = TestDocuments.all(http, store, auth(admin)).stream()
                .collect(Collectors.toMap(document -> (String) document.get("filename"), document -> document));
        var copy = listed.get("报损流程.md");
        assertThat(codes(copy)).containsExactly("DUPLICATE", "NEAR_DUPLICATE");
        assertThat(similar(copy, "DUPLICATE")).containsExactly("报损流程（机密副本）.md@体检重复机密库");
        assertThat(similar(copy, "NEAR_DUPLICATE")).containsExactly("报损流程（草稿）.md@体检重复门店库");
        assertThat(codes(draft)).containsExactly("NEAR_DUPLICATE");
        assertThat(similar(listed.get("报损流程（草稿）.md"), "NEAR_DUPLICATE"))
                .containsExactlyInAnyOrder("报损流程.md@体检重复门店库", "报损流程（机密副本）.md@体检重复机密库");

        String viewer = viewerOf(admin, store);
        var seen = TestDocuments.all(http, store, auth(viewer)).stream()
                .collect(Collectors.toMap(document -> (String) document.get("filename"), document -> document));
        assertThat(codes(seen.get("报损流程.md"))).as("机密库里的副本，对只能看门店库的人不提示")
                .containsExactly("NEAR_DUPLICATE");
        assertThat(similar(seen.get("报损流程（草稿）.md"), "NEAR_DUPLICATE"))
                .containsExactly("报损流程.md@体检重复门店库");
        assertThat(seen.toString()).doesNotContain("体检重复机密库").doesNotContain("机密副本");
        assertThat(summary(viewer, "体检重复门店库")).containsEntry("needsWorkCount", 2);
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> document) {
        return ((List<Map<String, Object>>) document.get("findings")).stream()
                .map(finding -> (String) finding.get("code")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> similar(Map<String, Object> document, String code) {
        return ((List<Map<String, Object>>) document.get("findings")).stream()
                .filter(finding -> code.equals(finding.get("code")))
                .flatMap(finding -> ((List<Map<String, Object>>) finding.get("similar")).stream())
                .map(other -> other.get("filename") + "@" + other.get("knowledgeBaseName")).toList();
    }

    /** 有 kb:view、只被授权了这一个库的管理员。 */
    private String viewerOf(String admin, long knowledgeBaseId) {
        var role = call(admin, HttpMethod.POST, "/api/roles",
                Map.of("name", "体检查看", "permissions", List.of("kb:view")));
        long roleId = ((Number) role.get("id")).longValue();
        var user = call(admin, HttpMethod.POST, "/api/users", Map.of("username", "health01", "displayName", "体检查看",
                "password", "staff-password", "roleIds", List.of(roleId)));
        call(admin, HttpMethod.PUT, "/api/knowledge-bases/" + knowledgeBaseId + "/members/" + user.get("id"), null);
        return TestLogin.token(http, "health01", "staff-password");
    }

    private Map<String, Object> summary(String token, String name) {
        var page = page(token, "/api/knowledge-bases/page?keyword=" + name);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) page.get("items");
        return items.getFirst();
    }

    private Map<String, Object> page(String token, String path) {
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> call(String token, HttpMethod method, String path, Object body) {
        var response = http.exchange(path, method, new HttpEntity<>(body, auth(token)), MAP);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isTrue();
        return response.getBody();
    }

    private long createKnowledgeBase(String token, String name) {
        return ((Number) call(token, HttpMethod.POST, "/api/knowledge-bases", Map.of("name", name)).get("id"))
                .longValue();
    }

    private Map<String, Object> upload(String token, long knowledgeBaseId, String filename, String content) {
        return upload(token, knowledgeBaseId, filename, content.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> upload(String token, long knowledgeBaseId, String filename, byte[] content) {
        var file = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange("/api/knowledge-bases/{id}/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP, knowledgeBaseId);
        assertThat(response.getStatusCode()).as(filename).isEqualTo(HttpStatus.CREATED);
        var processed = TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), token);
        assertThat(processed).as(filename).containsEntry("status", "READY");
        return processed;
    }

    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 足够长（切出 5 块以上）的中文文本；withHeadings 时分成几章。 */
    private static String text(String topic, boolean withHeadings) {
        return IntStream.range(0, 6).mapToObj(i -> (withHeadings ? "# 第" + (i + 1) + "章\n" : "")
                        + (topic + "第" + i + "部分：按门店规范逐项核对，记录差异并当天上报。").repeat(30))
                .collect(Collectors.joining("\n\n"));
    }

    /** 截图为主的 Word：两句话和若干张截图。 */
    private static byte[] wordWithPictures(int pictures) throws IOException {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("收银系统登录后按下面的截图操作。");
            for (int i = 0; i < pictures; i++) {
                try {
                    document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png(i + 1)),
                            PictureType.PNG, "step" + i + ".png", 9525, 9525);
                } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
                    throw new IOException(e);
                }
            }
            document.createParagraph().createRun().setText("完成后退出系统。");
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 足够长的 Word：styled 时章节标题用“标题 1”样式，否则只是普通段落。 */
    private static byte[] word(boolean styled) throws IOException {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            for (int chapter = 0; chapter < 6; chapter++) {
                var title = document.createParagraph();
                if (styled) {
                    title.setStyle("Heading1");
                }
                title.createRun().setText("陈列第" + (chapter + 1) + "章");
                document.createParagraph().createRun()
                        .setText(("陈列第" + chapter + "章：货架按品类分区，临期商品前置，价签与商品一一对应。").repeat(30));
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 几页英文的 PDF（测试字体只能写英文），够切出 5 块以上。 */
    private static byte[] longPdf() throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int page = 0; page < 8; page++) {
                var pdfPage = new PDPage();
                document.addPage(pdfPage);
                try (var content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.newLineAtOffset(40, 750);
                    for (int line = 0; line < 60; line++) {
                        content.showText("Page " + page + " line " + line
                                + ": check the shelf labels, rotate stock and log every gap before closing.");
                        content.newLineAtOffset(0, -12);
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 一个工作表，表头加 rows 行数据。 */
    private static byte[] excel(int rows) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("排期");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("日期");
            header.createCell(1).setCellValue("活动");
            for (int i = 1; i <= rows; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue("第" + i + "天");
                row.createCell(1).setCellValue("活动" + i);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] png(int size) throws IOException {
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
