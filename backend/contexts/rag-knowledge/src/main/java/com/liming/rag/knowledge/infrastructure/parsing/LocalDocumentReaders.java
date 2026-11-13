package com.liming.rag.knowledge.infrastructure.parsing;

import com.liming.rag.knowledge.application.ingestion.DocumentParseException;
import com.liming.rag.knowledge.application.DocumentReaders;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

/**
 * 本地读取 TXT / Markdown（UTF-8）、PDF（PDFBox）、Word .docx、Excel .xlsx / .xls 和 PowerPoint .pptx（POI），不调用外部服务。
 *
 * <p>Word 的标题样式转成 Markdown 标题；表格第一行当表头，其余每行转成“表头：值；表头：值”，
 * 一行一条，检索时每条都带着列名。Excel 每个可见工作表一节，规则同 Word 表格，单元格按 Excel 里显示的样子输出。
 * PowerPoint 每页一节，页标题当章节标题（没有标题用页码），正文、表格和备注跟在后面；只有图片的页不输出。
 * PDF 没有可靠的标题信息，只按阅读顺序提取文字。
 *
 * <p>同时记下资料体检用的信号（#218）：图片数和最大一张表格的行数。
 */
@Component
final class LocalDocumentReaders implements DocumentReaders {

    /** 中文 Word 的内置标题样式名是“heading 1”，样式 ID 可能是“Heading1”或“1”；自定义样式常叫“标题 1”。 */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|标题)\\s*([1-6])$");
    /** 少于这么多可见字符，基本可以判断是扫描件或图片。 */
    private static final int MIN_PDF_CHARS = 20;

    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx", "xlsx", "xls", "pptx");
    /** 幻灯片上不当正文的占位符：标题单独作章节标题，页码、日期、页脚每页重复。 */
    private static final Set<Placeholder> NOT_BODY = EnumSet.of(Placeholder.TITLE, Placeholder.CENTERED_TITLE,
            Placeholder.SLIDE_NUMBER, Placeholder.DATETIME, Placeholder.FOOTER);

    @Override
    public boolean supports(String extension) {
        return EXTENSIONS.contains(extension);
    }

    @Override
    public Text read(String filename, byte[] content) {
        var dot = filename.lastIndexOf('.');
        var extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!supports(extension)) {
            throw new IllegalArgumentException("不支持的文件类型：" + filename);
        }
        var out = new Sink();
        switch (extension) {
            case "pdf" -> pdf(content, out);
            case "docx" -> word(content, out);
            case "xlsx", "xls" -> excel(content, out);
            case "pptx" -> powerPoint(content, out);
            default -> out.text.append(utf8(content));
        }
        if (out.text.toString().isBlank()) {
            throw new DocumentParseException("文件里没有可检索的文字", null);
        }
        return new Text(out.text.toString(), out.images, out.largestTableRows);
    }

    /** 一次读取的输出：正文，以及边读边记的图片数和最大表格行数。 */
    private static final class Sink {
        private final StringBuilder text = new StringBuilder();
        private int images;
        private int largestTableRows;
    }

    private static String utf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DocumentParseException("文件不是 UTF-8 编码，请用“另存为 UTF-8”后重新上传", e);
        }
    }

    private static void pdf(byte[] content, Sink out) {
        try (var document = Loader.loadPDF(content)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var text = stripper.getText(document);
            if (text.replaceAll("\\s", "").length() < MIN_PDF_CHARS) {
                throw new DocumentParseException("PDF 里提取不到文字，可能是扫描件或图片；目前还不支持扫描件，请上传可以复制文字的 PDF", null);
            }
            out.text.append(text);
            for (var page : document.getPages()) {
                out.images += pdfImages(page.getResources());
            }
        } catch (InvalidPasswordException e) {
            throw new DocumentParseException("PDF 有密码保护，请去掉密码后重新上传", e);
        } catch (IOException e) {
            throw new DocumentParseException("文件已损坏或不是有效的 PDF", e);
        }
    }

    /** 一页上的图片：页面资源里的图片对象（同一张图在页上画几次只算一张）。 */
    private static int pdfImages(PDResources resources) throws IOException {
        if (resources == null) {
            return 0;
        }
        int images = 0;
        for (var name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) {
                images++;
            }
        }
        return images;
    }

    private static void word(byte[] content, Sink out) {
        try (var document = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    paragraph(document, paragraph, out.text);
                } else if (element instanceof XWPFTable table) {
                    table(table, out);
                }
            }
            out.images = document.getAllPictures().size();
        } catch (IOException | RuntimeException e) {
            // POI 对非 OOXML 文件会抛各种运行时异常（NotOfficeXmlFileException 等），统一当作文件损坏。
            throw new DocumentParseException("文件已损坏或不是有效的 Word 文档（.docx）；旧版 .doc 请先另存为 .docx", e);
        }
    }

    private static void excel(byte[] content, Sink out) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            var formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i)) {
                    continue;
                }
                var sheet = workbook.getSheetAt(i);
                var rows = rows(sheet, formatter, evaluator);
                if (rows.size() > 1) {
                    out.text.append("\n## ").append(sheet.getSheetName()).append('\n');
                    rows(rows, out);
                }
            }
        } catch (EncryptedDocumentException e) {
            throw new DocumentParseException("Excel 有密码保护，请去掉密码后重新上传", e);
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("文件已损坏或不是有效的 Excel 文件（.xlsx / .xls）", e);
        }
    }

    private static void powerPoint(byte[] content, Sink out) {
        try (var show = new XMLSlideShow(new ByteArrayInputStream(content))) {
            var slides = show.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                slide(slides.get(i), i + 1, out);
            }
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException("文件已损坏或不是有效的 PowerPoint 文件（.pptx）；旧版 .ppt 请先另存为 .pptx", e);
        }
    }

    /** 一页一节：标题当章节标题，没有标题用页码；没有文字的页（只有图片）不输出，免得产生空分块。 */
    private static void slide(XSLFSlide slide, int number, Sink out) {
        var body = new Sink();
        shapes(slide.getShapes(), body);
        notes(slide.getNotes(), body.text);
        out.images += body.images;
        out.largestTableRows = Math.max(out.largestTableRows, body.largestTableRows);
        var title = title(slide);
        if (body.text.isEmpty() && title.isEmpty()) {
            return;
        }
        out.text.append("\n## ").append(title.isEmpty() ? "第 " + number + " 页" : title).append('\n')
                .append(body.text);
    }

    /** 页标题；POI 的 {@code getTitle()} 不认封面页常用的居中标题，这里两种都认。 */
    private static String title(XSLFSlide slide) {
        return Arrays.stream(slide.getPlaceholders())
                .filter(shape -> shape.getTextType() == Placeholder.TITLE
                        || shape.getTextType() == Placeholder.CENTERED_TITLE)
                .map(shape -> singleLine(shape.getText()))
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
    }

    private static void shapes(List<XSLFShape> shapes, Sink out) {
        for (var shape : shapes) {
            if (shape instanceof XSLFTable table) {
                rows(table.getRows().stream()
                        .map(row -> row.getCells().stream().map(cell -> singleLine(cell.getText())).toList())
                        .toList(), out);
            } else if (shape instanceof XSLFGroupShape group) {
                shapes(group.getShapes(), out);
            } else if (shape instanceof XSLFPictureShape) {
                out.images++;
            } else if (shape instanceof XSLFTextShape textShape && !NOT_BODY.contains(textShape.getTextType())) {
                lines(textShape.getText(), out.text);
            }
        }
    }

    private static void notes(XSLFNotes notes, StringBuilder text) {
        if (notes == null) {
            return;
        }
        for (var shape : notes.getPlaceholders()) {
            var line = shape.getText().strip();
            if (shape.getTextType() == Placeholder.BODY && !line.isEmpty()) {
                text.append("备注：").append(line).append('\n');
            }
        }
    }

    private static void lines(String content, StringBuilder text) {
        content.lines().map(String::strip).filter(line -> !line.isEmpty())
                .forEach(line -> text.append(line).append('\n'));
    }

    private static String singleLine(String content) {
        return content.replaceAll("\\s+", " ").strip();
    }

    /** 工作表里有内容的行，每格换成单行文字。 */
    private static List<List<String>> rows(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        var rows = new ArrayList<List<String>>();
        for (var row : sheet) {
            var cells = IntStream.range(0, Math.max(row.getLastCellNum(), 0))
                    .mapToObj(c -> cellText(row.getCell(c), formatter, evaluator))
                    .toList();
            if (cells.stream().anyMatch(cell -> !cell.isEmpty())) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).replaceAll("\\s+", " ").strip();
    }

    private static void paragraph(XWPFDocument document, XWPFParagraph paragraph, StringBuilder text) {
        var line = paragraph.getText().strip();
        if (line.isEmpty()) {
            return;
        }
        int level = headingLevel(document, paragraph);
        if (level > 0) {
            text.append('\n').append("#".repeat(level)).append(' ').append(line).append('\n');
        } else {
            text.append(line).append('\n');
        }
    }

    private static int headingLevel(XWPFDocument document, XWPFParagraph paragraph) {
        var styleId = paragraph.getStyleID();
        if (styleId == null) {
            return 0;
        }
        var style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
        for (var candidate : List.of(style == null ? "" : style.getName(), styleId)) {
            var matcher = HEADING_STYLE.matcher(candidate.strip());
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 0;
    }

    private static void table(XWPFTable table, Sink out) {
        rows(table.getRows().stream()
                .map(row -> row.getTableCells().stream().map(LocalDocumentReaders::cell).toList())
                .toList(), out);
    }

    /** 第一行当表头，其余每行转成“表头：值；表头：值”，空单元格跳过；记下最大一张表的数据行数。 */
    private static void rows(List<List<String>> rows, Sink out) {
        if (rows.isEmpty()) {
            return;
        }
        out.largestTableRows = Math.max(out.largestTableRows, rows.size() - 1);
        var text = out.text;
        var header = rows.getFirst();
        for (var cells : rows.subList(1, rows.size())) {
            var line = IntStream.range(0, cells.size())
                    .filter(i -> !cells.get(i).isEmpty())
                    .mapToObj(i -> (i < header.size() && !header.get(i).isEmpty() ? header.get(i) + "：" : "")
                            + cells.get(i))
                    .collect(Collectors.joining("；"));
            if (!line.isEmpty()) {
                text.append(line).append('\n');
            }
        }
        text.append('\n');
    }

    private static String cell(XWPFTableCell cell) {
        return cell.getText().replaceAll("\\s+", " ").strip();
    }
}
