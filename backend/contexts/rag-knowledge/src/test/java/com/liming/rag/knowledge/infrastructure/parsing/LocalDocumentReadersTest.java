package com.liming.rag.knowledge.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.knowledge.application.ingestion.DocumentParseException;
import com.liming.rag.knowledge.application.DocumentReaders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalDocumentReadersTest {

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
    @DisplayName("文本：按 UTF-8 读取；不是 UTF-8 或没有文字时给出原因")
    void readsPlainText() {
        assertThat(text("收银.md", "# 收银\n\n交班清点备用金。".getBytes(StandardCharsets.UTF_8))).contains("# 收银");

        assertThatThrownBy(() -> text("旧编码.txt", new byte[] {(byte) 0xC1, (byte) 0xD9, (byte) 0xC6}))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> text("空白.txt", " \n ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("没有可检索的文字");
    }

    @Test
    @DisplayName("Excel：每个工作表一节，每行转成“表头：值”，按显示格式输出数字、日期和公式；隐藏表和空行跳过")
    void readsExcel() throws IOException {
        var text = text("价目表.xlsx", excel(new XSSFWorkbook()));

        assertThat(text).contains("## 价目表\n")
                .contains("商品：青柚气泡水；价格：5.50；上架日期：2026/10/27；两份价格：11.00")
                .contains("商品：海盐苏打；价格：4.00")
                .doesNotContain("草稿").doesNotContain("；；");
        assertThat(text("价目表.xls", excel(new HSSFWorkbook()))).contains("商品：青柚气泡水；价格：5.50");
        assertThatThrownBy(() -> text("损坏.xlsx", "not a xlsx".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏");
    }

    @Test
    @DisplayName("PowerPoint：每页标题成为章节，正文、表格（“表头：值”）和备注按页输出；只有图片的页不输出（#217）")
    void readsPowerPoint() throws IOException {
        var text = text("新员工培训.pptx", slides());

        assertThat(text).contains("## 新员工培训\n门店运营部\n")
                .contains("## 收银交班\n交班时清点备用金。\n两人核对后签字。\n备注：讲到这里演示一次点钞。\n")
                .contains("## 冷柜陈列\n商品：青柚气泡水；货架：A-03\n")
                .contains("## 第 5 页\n没有标题的页按页码命名。\n")
                .doesNotContain("第 4 页");
        assertThat(text.indexOf("## 收银交班")).isLessThan(text.indexOf("## 冷柜陈列"));
        assertThatThrownBy(() -> text("损坏.pptx", "not a pptx".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏").hasMessageContaining(".ppt");
        assertThatThrownBy(() -> text("全是图片.pptx", pictureOnlySlides()))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("没有可检索的文字");
    }

    @Test
    @DisplayName("不支持的类型、没有扩展名直接拒绝；扩展名不分大小写（#145 C8）")
    void rejectsUnsupportedTypes() {
        assertThat(readers.supports("docx")).isTrue();
        assertThat(readers.supports("pptx")).isTrue();
        assertThat(readers.supports("doc")).isFalse();
        assertThat(readers.supports("ppt")).isFalse();
        assertThatThrownBy(() -> text("旧版.doc", new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> text("没有扩展名", new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThat(text("规范.MD", "# 收银".getBytes(StandardCharsets.UTF_8))).isEqualTo("# 收银");
    }

    @Test
    @DisplayName("Word：普通样式、没有样式定义的数字样式 ID 按正文；空段落跳过；表格空单元格跳过、没有表头的列只写值、只有表头的表格不输出（#145 C8）")
    void handlesWordEdgeCases() throws IOException {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var normal = document.createParagraph();
            normal.setStyle("Normal");
            normal.createRun().setText("正文段落");
            document.createParagraph().createRun().setText("   ");
            var heading3 = document.createParagraph();
            heading3.setStyle("3");
            heading3.createRun().setText("样式表里没有定义的 3 号样式");
            var table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("商品");
            table.getRow(0).getCell(1).setText("");
            table.getRow(1).getCell(0).setText("青柚气泡水");
            table.getRow(1).getCell(1).setText("冷柜第二层");
            table.getRow(1).getCell(2).setText("");
            var headerOnly = document.createTable(1, 1);
            headerOnly.getRow(0).getCell(0).setText("只有表头");
            document.write(out);

            var text = text("边界.docx", out.toByteArray());

            assertThat(text).contains("正文段落\n").contains("\n样式表里没有定义的 3 号样式\n").doesNotContain("#")
                    .contains("商品：青柚气泡水；冷柜第二层\n").doesNotContain("只有表头").doesNotContain("；；");
        }
    }

    @Test
    @DisplayName("Excel：有密码保护时提示去掉密码；只有表头的工作表不输出；旧版 .xls 损坏时给出原因（#145 C8）")
    void handlesExcelEdgeCases() throws Exception {
        assertThatThrownBy(() -> text("加密.xlsx", encryptedExcel()))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("密码");
        assertThatThrownBy(() -> text("损坏.xls", "not a xls".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("损坏");

        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            workbook.createSheet("只有表头").createRow(0).createCell(0).setCellValue("商品");
            var sheet = workbook.createSheet("价目表");
            sheet.createRow(0).createCell(0).setCellValue("商品");
            sheet.createRow(1).createCell(0).setCellValue("海盐苏打");
            workbook.write(out);

            assertThat(text("两个表.xlsx", out.toByteArray())).doesNotContain("## 只有表头").contains("## 价目表\n")
                    .contains("商品：海盐苏打");
        }
    }

    /** 用密码加密的 .xlsx（POI 的 agile 加密，Excel 2010 以后的默认方式）。 */
    private static byte[] encryptedExcel() throws Exception {
        var plain = excel(new XSSFWorkbook());
        try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(); var out = new ByteArrayOutputStream()) {
            var info = new org.apache.poi.poifs.crypt.EncryptionInfo(org.apache.poi.poifs.crypt.EncryptionMode.agile);
            var encryptor = info.getEncryptor();
            encryptor.confirmPassword("门店密码");
            try (var stream = encryptor.getDataStream(fs)) {
                stream.write(plain);
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    /** 价目表：表头、两行数据、中间一个空行；另有一个隐藏的草稿表。 */
    static byte[] excel(Workbook workbook) throws IOException {
        try (workbook; var out = new ByteArrayOutputStream()) {
            var helper = workbook.getCreationHelper();
            var money = workbook.createCellStyle();
            money.setDataFormat(helper.createDataFormat().getFormat("0.00"));
            var date = workbook.createCellStyle();
            date.setDataFormat(helper.createDataFormat().getFormat("yyyy/m/d"));

            var sheet = workbook.createSheet("价目表");
            var header = sheet.createRow(0);
            String[] titles = {"商品", "价格", "上架日期", "两份价格"};
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
            }
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("青柚气泡水");
            first.createCell(1).setCellValue(5.5);
            first.getCell(1).setCellStyle(money);
            first.createCell(2).setCellValue(LocalDate.of(2026, 10, 27));
            first.getCell(2).setCellStyle(date);
            first.createCell(3).setCellFormula("B2*2");
            first.getCell(3).setCellStyle(money);
            var second = sheet.createRow(3);
            second.createCell(0).setCellValue("海盐苏打");
            second.createCell(1).setCellValue(4);
            second.getCell(1).setCellStyle(money);

            workbook.createSheet("草稿").createRow(0).createCell(0).setCellValue("草稿");
            workbook.setSheetHidden(1, true);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 合成课件：标题页、正文页（带备注）、表格页、只有图片的页、没有标题的页。 */
    private static byte[] slides() throws IOException {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var master = show.getSlideMasters().getFirst();
            var cover = show.createSlide(master.getLayout(SlideLayout.TITLE));
            cover.getPlaceholder(0).setText("新员工培训");
            cover.getPlaceholder(1).setText("门店运营部");
            var body = show.createSlide(master.getLayout(SlideLayout.TITLE_AND_CONTENT));
            body.getPlaceholder(0).setText("收银交班");
            body.getPlaceholder(1).setText("交班时清点备用金。\n两人核对后签字。");
            java.util.Arrays.stream(show.getNotesSlide(body).getPlaceholders())
                    .filter(shape -> shape.getTextType() == Placeholder.BODY)
                    .forEach(shape -> shape.setText("讲到这里演示一次点钞。"));
            var tablePage = show.createSlide(master.getLayout(SlideLayout.TITLE_ONLY));
            tablePage.getPlaceholder(0).setText("冷柜陈列");
            var table = tablePage.createTable(2, 2);
            table.getCell(0, 0).setText("商品");
            table.getCell(0, 1).setText("货架");
            table.getCell(1, 0).setText("青柚气泡水");
            table.getCell(1, 1).setText("A-03");
            show.createSlide().createPicture(show.addPicture(png(), PictureData.PictureType.PNG));
            show.createSlide().createTextBox().setText("没有标题的页按页码命名。");
            show.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pictureOnlySlides() throws IOException {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            show.createSlide().createPicture(show.addPicture(png(), PictureData.PictureType.PNG));
            show.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png", out);
        return out.toByteArray();
    }

    private String text(String filename, byte[] content) {
        return readers.read(filename, content);
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
