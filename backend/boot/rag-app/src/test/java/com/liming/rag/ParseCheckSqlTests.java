package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 解析核对脚本（#134）的 SQL（deploy/parse-check.sql）与资料体检（#218）：直接写入合成的信号，覆盖每条规则的阈值两侧，
 * 确认脚本的结论和管理端文档列表的体检结论一致（读同一份信号、同样的规则），并确认脚本不改数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ParseCheckSqlTests {

    /** 体检代码在脚本里的名称，与前端 FINDINGS 的名称一致。 */
    private static final Map<String, String> LABELS = Map.of(
            "NO_CHUNKS", "没有可检索的内容", "IMAGE_HEAVY", "疑似内容在图片里", "WORD_NO_HEADINGS", "Word 没用标题样式",
            "PDF_NO_STRUCTURE", "PDF 读不出章节", "NO_HEADINGS", "文档没有标题", "LARGE_TABLE", "表格过大",
            "GARBLED", "疑似乱码", "SCATTERED", "疑似版面散乱", "DUPLICATE", "与其他文档重复", "NEAR_DUPLICATE", "疑似重复");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("每条规则在阈值两侧各有样例：脚本的结论与文档列表的体检结论一致；失败、解析中照旧；只读")
    void reportsSameConclusionsAsDocumentList() throws Exception {
        long kb = jdbc.queryForObject(
                "insert into knowledge_base (tenant_id, name) values (1, '核对测试库') returning id", Long.class);
        long other = jdbc.queryForObject(
                "insert into knowledge_base (tenant_id, name) values (1, '核对测试库二') returning id", Long.class);
        // 每个样例旁写明期望；名字说明它在阈值的哪一侧（可见字数 = 总字数 - 空白）。
        var expected = Map.ofEntries(
                Map.entry("制度.docx", signals(kb, "制度.docx", 8, 0, 2, 4000, 400, 0, 0, List.of())),
                Map.entry("截图手册.docx", signals(kb, "截图手册.docx", 2, 10, 0, 2099, 100, 0, 0,
                        List.of("IMAGE_HEAVY"))),
                Map.entry("图文手册.docx", signals(kb, "图文手册.docx", 2, 10, 1, 2100, 100, 0, 0, List.of())),
                Map.entry("一张图.pptx", signals(kb, "一张图.pptx", 1, 1, 1, 120, 10, 0, 0, List.of())),
                Map.entry("两张图.pptx", signals(kb, "两张图.pptx", 1, 2, 1, 120, 10, 0, 0, List.of("IMAGE_HEAVY"))),
                Map.entry("无样式.docx", signals(kb, "无样式.docx", 5, 0, 0, 3000, 300, 0, 0,
                        List.of("WORD_NO_HEADINGS"))),
                Map.entry("短文.docx", signals(kb, "短文.docx", 4, 0, 0, 2000, 200, 0, 0, List.of())),
                Map.entry("手册.pdf", signals(kb, "手册.pdf", 5, 0, 0, 3000, 300, 0, 0, List.of("PDF_NO_STRUCTURE"))),
                Map.entry("说明.md", signals(kb, "说明.md", 5, 0, 0, 3000, 300, 0, 0, List.of("NO_HEADINGS"))),
                Map.entry("排期.xlsx", signals(kb, "排期.xlsx", 9, 0, 1, 9000, 900, 0, 500, List.of("LARGE_TABLE"))),
                Map.entry("小排期.xlsx", signals(kb, "小排期.xlsx", 9, 0, 1, 9000, 900, 0, 499, List.of())),
                Map.entry("乱码.pdf", signals(kb, "乱码.pdf", 1, 0, 0, 1000, 100, 10, 0, List.of("GARBLED"))),
                Map.entry("几乎没乱码.pdf", signals(kb, "几乎没乱码.pdf", 1, 0, 0, 1000, 100, 9, 0, List.of())),
                Map.entry("散乱.pdf", signals(kb, "散乱.pdf", 1, 0, 0, 1000, 400, 0, 0, List.of("SCATTERED"))),
                Map.entry("截图多又散乱.pdf", signals(kb, "截图多又散乱.pdf", 1, 3, 0, 500, 300, 0, 0,
                        List.of("IMAGE_HEAVY", "SCATTERED"))),
                Map.entry("旧文档.docx", legacy(kb, "旧文档.docx")));
        long copy = document(kb, "副本.txt", "READY", null, "same");
        long original = document(other, "原件.txt", "READY", null, "same");
        jdbc.update("update document set chunk_count = 1 where id in (?, ?)", copy, original);
        long draft = document(kb, "草稿.txt", "READY", null, "draft");
        jdbc.update("update document set chunk_count = 1 where id = ?", draft);
        jdbc.update("insert into document_overlap (tenant_id, document_id, other_document_id) values (1, ?, ?)", draft,
                original);
        document(kb, "扫描件.pdf", "FAILED", "PDF 里提取不到文字", "scan");
        int documentsBefore = jdbc.queryForObject("select count(*) from document", Integer.class);

        var rows = jdbc.queryForList(Files.readString(Path.of("../../../deploy/parse-check.sql")));
        var byName = rows.stream().filter(row -> List.of("核对测试库", "核对测试库二").contains(row.get("知识库")))
                .collect(Collectors.toMap(row -> (String) row.get("文档"), row -> row));
        var listed = TestDocuments.all(http, kb, adminHeaders()).stream()
                .collect(Collectors.toMap(document -> (String) document.get("filename"), document -> document));

        expected.forEach((filename, codes) -> {
            assertThat(codes(listed.get(filename))).as(filename).isEqualTo(codes);
            assertThat(byName.get(filename)).as(filename).containsEntry("结论", conclusion(codes));
        });
        assertThat(codes(listed.get("副本.txt"))).containsExactly("DUPLICATE");
        assertThat(byName.get("副本.txt")).containsEntry("结论", "与其他文档重复")
                .containsEntry("重复的文档", "核对测试库二/原件.txt");
        assertThat(codes(listed.get("草稿.txt"))).containsExactly("NEAR_DUPLICATE");
        assertThat(byName.get("草稿.txt")).containsEntry("结论", "疑似重复");
        assertThat(byName.get("扫描件.pdf")).containsEntry("结论", "失败：PDF 里提取不到文字");
        assertThat(listed.get("扫描件.pdf")).containsEntry("findings", null);
        assertThat((BigDecimal) byName.get("乱码.pdf").get("乱码占比(%)")).isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForObject("select count(*) from document", Integer.class))
                .as("核对只读，不改数据").isEqualTo(documentsBefore);
    }

    /** 一份可用的文档，带着入库时的信号；返回它应得的体检代码（按规则在 SQL 里的顺序）。 */
    private List<String> signals(long kb, String filename, int chunks, int images, int headingDepth, int totalChars,
            int blankChars, int garbledChars, int largestTableRows, List<String> expected) {
        long id = document(kb, filename, "READY", null, filename);
        jdbc.update("""
                update document set chunk_count = ?, image_count = ?, heading_depth = ?, total_chars = ?, blank_chars = ?,
                       garbled_chars = ?, largest_table_rows = ? where id = ?
                """, chunks, images, headingDepth, totalChars, blankChars, garbledChars, largestTableRows, id);
        return expected;
    }

    /** 升级前入库的文档：图片数、表格行没有记下，不判断截图和表格。 */
    private List<String> legacy(long kb, String filename) {
        long id = document(kb, filename, "READY", null, filename);
        jdbc.update("update document set chunk_count = 2, heading_depth = 1, total_chars = 100, blank_chars = 10,"
                + " garbled_chars = 0 where id = ?", id);
        return List.of();
    }

    private static String conclusion(List<String> codes) {
        return codes.isEmpty() ? "正常" : codes.stream().map(LABELS::get).collect(Collectors.joining("、"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> document) {
        return ((List<Map<String, Object>>) document.get("findings")).stream()
                .map(finding -> (String) finding.get("code")).toList();
    }

    private HttpHeaders adminHeaders() {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestLogin.token(http, TestLogin.ADMIN_USERNAME, TestLogin.ADMIN_PASSWORD));
        return headers;
    }

    private long document(long kb, String filename, String status, String error, String content) {
        return jdbc.queryForObject("""
                insert into document (tenant_id, knowledge_base_id, filename, media_type, sha256, size_bytes, status, error)
                values (1, ?, ?, 'application/octet-stream', md5(?) || md5(?), 1, ?, ?) returning id
                """, Long.class, kb, filename, content, content, status, error);
    }
}
