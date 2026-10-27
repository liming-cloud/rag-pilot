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

class DocumentReadersTest {

    private final DocumentReaders readers = new LocalDocumentReaders();

    @Test
    @DisplayName("Word：标题样式转成章节，表格按行转成“表头：值”")
    void parsesWord() throws IOException {
        var text = text("收银规范.docx", word());

        assertThat(text).contains("# 门店收银\n").contains("## 交班\n").contains("交班时清点备用金。")
                .contains("商品：青柚气泡水；货架：A-03");
        assertThat(text.indexOf("# 门店收银")).isLessThan(text.indexOf("交班时清点备用金"));
    }

    @Test
    @DisplayName("PDF：按阅读顺序提取文字")
    void parsesPdf() throws IOException {
        assertThat(text("handover.pdf", pdf("Count the cash float before handover.", false)))
                .contains("Count the cash float before handover.");
    }

    @Test
    @DisplayName("提取不到文字的 PDF、加密 PDF、损坏文件都给出中文原因")
    void explainsUnreadableFiles() throws IOException {
        assertThatThrownBy(() -> text("扫描件.pdf", pdf(null, false)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("扫描件");
        assertThatThrownBy(() -> text("加密.pdf", pdf("secret", true)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("密码");
        assertThatThrownBy(() -> text("损坏.docx", "not a docx".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏");
        assertThatThrownBy(() -> text("损坏.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏");
    }

    @Test
    @DisplayName("文本：按 UTF-8 读取；不是 UTF-8 或没有文字时给出原因；元数据带文件名")
    void readsPlainText() {
        var document = readers.forFile("收银.md", "# 收银\n\n交班清点备用金。".getBytes(StandardCharsets.UTF_8)).get()
                .getFirst();
        assertThat(document.getText()).contains("# 收银");
        assertThat(document.getMetadata()).containsEntry(DocumentReaders.FILENAME, "收银.md");

        assertThatThrownBy(() -> text("旧编码.txt", new byte[] {(byte) 0xC1, (byte) 0xD9, (byte) 0xC6}))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> text("空白.txt", " \n ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("没有可检索的文字");
    }

    private String text(String filename, byte[] content) {
        return readers.forFile(filename, content).get().getFirst().getText();
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
