package com.liming.rag.ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

/**
 * 本地读取 TXT / Markdown（UTF-8）、PDF（PDFBox）和 Word .docx（POI），不调用外部服务。
 *
 * <p>Word 的标题样式转成 Markdown 标题；表格第一行当表头，其余每行转成“表头：值；表头：值”，
 * 一行一条，检索时每条都带着列名。PDF 没有可靠的标题信息，只按阅读顺序提取文字。
 */
final class LocalDocumentReaders implements DocumentReaders {

    /** 中文 Word 的内置标题样式名是“heading 1”，样式 ID 可能是“Heading1”或“1”；自定义样式常叫“标题 1”。 */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|标题)\\s*([1-6])$");
    /** 少于这么多可见字符，基本可以判断是扫描件或图片。 */
    private static final int MIN_PDF_CHARS = 20;

    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx");

    @Override
    public boolean supports(String extension) {
        return EXTENSIONS.contains(extension);
    }

    @Override
    public DocumentReader forFile(String filename, byte[] content) {
        var dot = filename.lastIndexOf('.');
        var extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!supports(extension)) {
            throw new IllegalArgumentException("不支持的文件类型：" + filename);
        }
        return () -> {
            var text = switch (extension) {
                case "pdf" -> pdf(content);
                case "docx" -> word(content);
                default -> utf8(content);
            };
            if (text.isBlank()) {
                throw new DocumentParseException("文件里没有可检索的文字", null);
            }
            return List.of(new Document(text, Map.of(FILENAME, filename)));
        };
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
        var header = rows.getFirst().getTableCells().stream().map(LocalDocumentReaders::cell).toList();
        for (var row : rows.subList(1, rows.size())) {
            var cells = row.getTableCells().stream().map(LocalDocumentReaders::cell).toList();
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
