package com.liming.rag.knowledge.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 识别图片里的文字（#223）：识别结果跟在图片所在的段落或页后面，引用仍指向原文档。 */
class LocalDocumentReadersOcrTest {

    private final OcrStub ocr = new OcrStub();
    private final LocalDocumentReaders readers = new LocalDocumentReaders(new ImageText(ocr.url()));

    LocalDocumentReadersOcrTest() throws IOException {
    }

    @AfterEach
    void stop() {
        ocr.close();
    }

    @Test
    @DisplayName("Word：图片的文字跟在图片所在段落后面；同一张图只识别一次，太小的图标不识别")
    void word() throws IOException {
        var text = readers.read("截图手册.docx", wordWithPictures(screenshot(40), screenshot(40), screenshot(8)));

        assertThat(text.content()).isEqualTo("按下图操作。\n图片文字：截图文字 1\n最后签字。\n");
        assertThat(ocr.requests()).isEqualTo(1);
        assertThat(text.images()).isEqualTo(2);
        assertThat(text.imageTextPending()).isFalse();
    }

    @Test
    @DisplayName("PowerPoint：图片的文字算在所在页里，只有图片的页也能输出")
    void powerPoint() throws IOException {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var master = show.getSlideMasters().getFirst();
            var titled = show.createSlide(master.getLayout(SlideLayout.TITLE_ONLY));
            titled.getPlaceholder(0).setText("收银交班");
            titled.createPicture(show.addPicture(screenshot(40), PictureData.PictureType.PNG));
            show.createSlide().createPicture(show.addPicture(screenshot(50), PictureData.PictureType.PNG));
            show.write(out);

            assertThat(readers.read("培训.pptx", out.toByteArray()).content())
                    .isEqualTo("\n## 收银交班\n图片文字：截图文字 1\n\n## 第 2 页\n图片文字：截图文字 2\n");
        }
    }

    @Test
    @DisplayName("PDF：每页的图片文字跟在这一页的文字后面；只有图片的扫描页识别出文字也能入库")
    void pdf() throws IOException {
        var text = readers.read("截图手册.pdf", pdf("Follow the screenshots below.", true, true)).content();

        assertThat(text).containsSubsequence("Follow the screenshots below.", "图片文字：截图文字 1",
                "Second page.", "图片文字：截图文字 2");
        ocr.respond(200, """
                {"0":{"rec_txt":"交班时两人一起清点备用金并在交班表上签字","dt_boxes":[[0,0],[90,0],[90,20],[0,20]],"score":0.99}}
                """);
        assertThat(readers.read("扫描件.pdf", pdf(null, true, false)).content())
                .contains("图片文字：交班时两人一起清点备用金并在交班表上签字");
    }

    @Test
    @DisplayName("识别服务不可用：照常读出可选中的文字，本文档剩下的图不再请求，标为图片文字待识别")
    void unavailable() throws IOException {
        ocr.respond(503, "busy");

        var text = readers.read("截图手册.docx", wordWithPictures(screenshot(40), screenshot(50)));

        assertThat(text.content()).isEqualTo("按下图操作。\n最后签字。\n");
        assertThat(ocr.requests()).isEqualTo(1);
        assertThat(text.imageTextPending()).isTrue();
    }

    @Test
    @DisplayName("没配置识别服务：不识别，有图片的文档标为图片文字待识别，以后配置了再补")
    void disabled() throws IOException {
        var off = new LocalDocumentReaders(new ImageText(""));

        assertThat(off.read("截图手册.docx", wordWithPictures(screenshot(40))).imageTextPending()).isTrue();
        assertThat(off.read("收银规范.docx", LocalDocumentReadersTest.word()).imageTextPending()).isFalse();
        assertThat(off.recognizesImages()).isFalse();
        assertThat(readers.recognizesImages()).isTrue();
    }

    /** size 像素见方的图，尺寸不同内容就不同。 */
    private static byte[] screenshot(int size) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** 一句话，每张图单独一段，最后一句话。 */
    private static byte[] wordWithPictures(byte[]... pictures) throws IOException {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("按下图操作。");
            for (int i = 0; i < pictures.length; i++) {
                try {
                    document.createParagraph().createRun().addPicture(new ByteArrayInputStream(pictures[i]),
                            PictureType.PNG, "step" + i + ".png", 9525, 9525);
                } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
                    throw new IOException(e);
                }
            }
            document.createParagraph().createRun().setText("最后签字。");
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 第一页一行英文（text 为 null 时没有文字）和一张图；secondPage 时再加一页文字和另一张图。 */
    private static byte[] pdf(String text, boolean picture, boolean secondPage) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            page(document, text, picture ? screenshot(40) : null);
            if (secondPage) {
                page(document, "Second page.", screenshot(50));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void page(PDDocument document, String text, byte[] picture) throws IOException {
        var page = new PDPage();
        document.addPage(page);
        try (var content = new PDPageContentStream(document, page)) {
            if (text != null) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            if (picture != null) {
                content.drawImage(PDImageXObject.createFromByteArray(document, picture, "step"), 72, 500, 40, 40);
            }
        }
    }
}
