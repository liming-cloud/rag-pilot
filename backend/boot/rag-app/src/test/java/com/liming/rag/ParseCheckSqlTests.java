package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 解析核对脚本（#134）的 SQL（deploy/parse-check.sql）：用合成的分块验证各列和结论，并确认它不改数据。 */
@SpringBootTest
class ParseCheckSqlTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("按文档给出分块数、标题层级、表格行、乱码和空白占比与结论；只读")
    void reportsParseQualityPerDocument() throws Exception {
        long kb = jdbc.queryForObject(
                "insert into knowledge_base (tenant_id, name) values (1, '核对测试库') returning id", Long.class);
        long good = document(kb, "制度.docx", "READY", null);
        chunk(good, 0, "制度 > 考勤 > 迟到", "迟到三次记一次旷工。");
        chunk(good, 1, "制度 > 考勤", "规格：大；价格：10\n规格：小；价格：6\n普通的一行话。");
        long garbled = document(kb, "乱码.pdf", "READY", null);
        chunk(garbled, 0, "乱码", "正常文字��������������������正常文字");
        long scattered = document(kb, "散乱.pdf", "READY", null);
        chunk(scattered, 0, "散乱", "字 ".repeat(30));
        long failed = document(kb, "扫描件.pdf", "FAILED", "PDF 里提取不到文字");
        long flat = document(kb, "无标题.docx", "READY", null);
        for (int i = 0; i < 5; i++) {
            chunk(flat, i, "无标题", "第" + i + "段内容。");
        }
        int documentsBefore = jdbc.queryForObject("select count(*) from document", Integer.class);

        var sql = Files.readString(Path.of("../../../deploy/parse-check.sql"));
        var rows = jdbc.queryForList(sql);

        assertThat(rows).hasSize(5);
        var byName = rows.stream().collect(Collectors.toMap(r -> (String) r.get("文档"), r -> r));
        assertThat(byName.get("制度.docx")).containsEntry("结论", "正常").containsEntry("知识库", "核对测试库");
        assertThat(number(byName.get("制度.docx"), "分块数")).isEqualTo(2);
        assertThat(number(byName.get("制度.docx"), "标题层级")).isEqualTo(2);
        assertThat(number(byName.get("制度.docx"), "表格行(估计)")).isEqualTo(2);
        assertThat(byName.get("乱码.pdf")).containsEntry("结论", "疑似乱码");
        assertThat((BigDecimal) byName.get("乱码.pdf").get("乱码占比(%)")).isGreaterThan(BigDecimal.ONE);
        assertThat(byName.get("散乱.pdf")).containsEntry("结论", "疑似版面散乱");
        assertThat(byName.get("扫描件.pdf")).containsEntry("结论", "失败：PDF 里提取不到文字");
        assertThat(number(byName.get("扫描件.pdf"), "分块数")).isZero();
        assertThat(byName.get("无标题.docx")).containsEntry("结论", "未识别标题");
        assertThat(number(byName.get("无标题.docx"), "标题层级")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from document", Integer.class))
                .as("核对只读，不改数据").isEqualTo(documentsBefore);
    }

    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private long document(long kb, String filename, String status, String error) {
        return jdbc.queryForObject("""
                insert into document (tenant_id, knowledge_base_id, filename, media_type, sha256, size_bytes, status, error)
                values (1, ?, ?, 'application/octet-stream', md5(?) || md5(?), 1, ?, ?) returning id
                """, Long.class, kb, filename, filename, filename, status, error);
    }

    private void chunk(long document, int ordinal, String headingPath, String content) {
        jdbc.update("""
                insert into chunk (tenant_id, knowledge_base_id, document_id, ordinal, heading_path, content, token_count)
                select 1, knowledge_base_id, id, ?, ?, ?, 1 from document where id = ?
                """, ordinal, headingPath, content, document);
        jdbc.update("update document set chunk_count = chunk_count + 1 where id = ?", document);
    }
}
