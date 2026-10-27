package com.liming.rag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private final DocumentParser parser = new OfficeDocumentParser();

    @Test
    @DisplayName("Word：标题样式转成章节，表格按行转成“表头：值”")
    void parsesWord() throws IOException {
        var text = parser.toText("收银规范.docx", word());

        assertThat(text).contains("# 门店收银\n").contains("## 交班\n").contains("交班时清点备用金。")
                .contains("商品：青柚气泡水；货架：A-03");
        assertThat(text.indexOf("# 门店收银")).isLessThan(text.indexOf("交班时清点备用金"));
    }

    @Test
    @DisplayName("PDF：按阅读顺序提取文字")
    void parsesPdf() throws IOException {
        assertThat(parser.toText("handover.pdf", pdf("Count the cash float before handover.", false)))
                .contains("Count the cash float before handover.");
    }

    @Test
    @DisplayName("提取不到文字的 PDF、加密 PDF、损坏文件都给出中文原因")
    void explainsUnreadableFiles() throws IOException {
        assertThatThrownBy(() -> parser.toText("扫描件.pdf", pdf(null, false)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("扫描件");
        assertThatThrownBy(() -> parser.toText("加密.pdf", pdf("secret", true)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("密码");
        assertThatThrownBy(() -> parser.toText("损坏.docx", "not a docx".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏");
        assertThatThrownBy(() -> parser.toText("损坏.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏");
    }

    static byte[] word() throws IOException {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("门店收银");
            var section = document.createParagraph();
            section.setStyle("Heading2");
            section.createRun().setText("交班");
            document.createParagraph().createRun().setText("交班时清点备用金。");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("商品");
            table.getRow(0).getCell(1).setText("货架");
            table.getRow(1).getCell(0).setText("青柚气泡水");
            table.getRow(1).getCell(1).setText("A-03");
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 测试 PDF 用标准 14 字体，只能写英文；text 为 null 时生成没有文字的页，模拟扫描件。 */
    static byte[] pdf(String text, boolean encrypted) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            if (encrypted) {
                var policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
                document.protect(policy);
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
