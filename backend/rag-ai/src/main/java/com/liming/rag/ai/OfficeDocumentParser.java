package com.liming.rag.ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/**
 * 本地解析 PDF（PDFBox）和 Word .docx（POI），不调用外部服务。
 *
 * <p>Word 的标题样式转成 Markdown 标题；表格第一行当表头，其余每行转成“表头：值；表头：值”，
 * 一行一条，检索时每条都带着列名。PDF 没有可靠的标题信息，只按阅读顺序提取文字。
 */
final class OfficeDocumentParser implements DocumentParser {

    /** 中文 Word 的内置标题样式名是“heading 1”，样式 ID 可能是“Heading1”或“1”；自定义样式常叫“标题 1”。 */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|标题)\\s*([1-6])$");
    /** 少于这么多可见字符，基本可以判断是扫描件或图片。 */
    private static final int MIN_PDF_CHARS = 20;

    @Override
    public boolean supports(String extension) {
        return extension.equals("pdf") || extension.equals("docx");
    }

    @Override
    public String toText(String filename, byte[] content) {
        var lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return pdf(content);
        }
        if (lower.endsWith(".docx")) {
            return word(content);
        }
        throw new IllegalArgumentException("不支持的文件类型：" + filename);
    }

    private static String pdf(byte[] content) {
        try (var document = Loader.loadPDF(content)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var text = stripper.getText(document);
            if (text.replaceAll("\\s", "").length() < MIN_PDF_CHARS) {
                throw new DocumentParseException("PDF 里提取不到文字，可能是扫描件或图片；目前还不支持扫描件，请上传可以复制文字的 PDF", null);
            }
            return text;
        } catch (InvalidPasswordException e) {
            throw new DocumentParseException("PDF 有密码保护，请去掉密码后重新上传", e);
        } catch (IOException e) {
            throw new DocumentParseException("文件已损坏或不是有效的 PDF", e);
        }
    }

    private static String word(byte[] content) {
        try (var document = new XWPFDocument(new ByteArrayInputStream(content))) {
            var text = new StringBuilder();
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    paragraph(document, paragraph, text);
                } else if (element instanceof XWPFTable table) {
                    table(table, text);
                }
            }
            return text.toString();
        } catch (IOException | RuntimeException e) {
            // POI 对非 OOXML 文件会抛各种运行时异常（NotOfficeXmlFileException 等），统一当作文件损坏。
            throw new DocumentParseException("文件已损坏或不是有效的 Word 文档（.docx）；旧版 .doc 请先另存为 .docx", e);
        }
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

    private static void table(XWPFTable table, StringBuilder text) {
        var rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        var header = rows.getFirst().getTableCells().stream().map(OfficeDocumentParser::cell).toList();
        for (var row : rows.subList(1, rows.size())) {
            var cells = row.getTableCells().stream().map(OfficeDocumentParser::cell).toList();
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
