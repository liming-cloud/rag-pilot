package com.liming.rag.knowledge.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按标题感知的文本分块：先按 Markdown 标题切成章节，再在章节内按段落装箱。
 *
 * <p>规则：分块不跨章节；单块目标不超过 {@link #MAX_TOKENS}；同一章节的相邻分块
 * 重叠约 {@link #OVERLAP_TOKENS} 个 token，避免答案正好落在切口上，重叠从一行或一句的开头开始；超长段落先按行切，
 * 再按句子、按字符切。以 {@link #TABLE_HEADER} 开头的段落是表格，放不下时按整行切成行组，每块带上表头（#225）。
 * 算法是确定性的，同样的输入总是得到同样的分块。
 */
public final class TextChunker {

    /** 分块规则的版本：规则改了就加一，升级后按旧版本切的文档由后台自动重新分块（#225）。 */
    public static final int VERSION = 2;
    public static final int MAX_TOKENS = 500;
    public static final int OVERLAP_TOKENS = 80;
    /** 表格的第一行以它开头，后面是列名；读取器写出，分块时据此认出表格。 */
    public static final String TABLE_HEADER = "【表头】";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;])|(?<=\\.\\s)");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？；!?;]");

    private final int maxTokens;
    private final int overlapTokens;

    public TextChunker() {
        this(MAX_TOKENS, OVERLAP_TOKENS);
    }

    public TextChunker(int maxTokens, int overlapTokens) {
        if (maxTokens <= 0 || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalStateException("分块参数不合法");
        }
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /**
     * @param title 文档标题，作为每个分块位置的第一级
     * @param text 文档全文
     * @return 按顺序排列的分块；全文没有可见文字时返回空列表
     */
    public List<Chunk> chunk(String title, String text) {
        var chunks = new ArrayList<Chunk>();
        for (var section : sections(title, normalize(text))) {
            pack(section, chunks);
        }
        return List.copyOf(chunks);
    }

    private static String normalize(String text) {
        var value = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            value = value.substring(1);
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private record Section(String headingPath, List<String> paragraphs) {
    }

    private static List<Section> sections(String title, String text) {
        var result = new ArrayList<Section>();
        var headings = new HeadingPath(title);
        var paragraphs = new ArrayList<String>();
        var paragraph = new StringBuilder();
        String path = title;
        for (var line : text.split("\n", -1)) {
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(paragraph, paragraphs);
                addSection(result, path, paragraphs);
                path = headings.enter(heading.group(1).length(), heading.group(2).strip());
            } else if (line.isBlank()) {
                flushParagraph(paragraph, paragraphs);
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line.strip());
            }
        }
        flushParagraph(paragraph, paragraphs);
        addSection(result, path, paragraphs);
        return result;
    }

    /** 当前所在的标题层级：进入一个标题时，先退出同级和更深的标题。 */
    private static final class HeadingPath {

        private final String title;
        private final List<String> headings = new ArrayList<>();
        private final List<Integer> levels = new ArrayList<>();

        HeadingPath(String title) {
            this.title = title;
        }

        String enter(int level, String heading) {
            while (!levels.isEmpty() && levels.getLast() >= level) {
                levels.removeLast();
                headings.removeLast();
            }
            levels.add(level);
            headings.add(heading);
            return title + " > " + String.join(" > ", headings);
        }
    }

    private static void flushParagraph(StringBuilder paragraph, List<String> paragraphs) {
        if (!paragraph.isEmpty()) {
            paragraphs.add(paragraph.toString());
            paragraph.setLength(0);
        }
    }

    private static void addSection(List<Section> result, String path, List<String> paragraphs) {
        if (!paragraphs.isEmpty()) {
            result.add(new Section(path, List.copyOf(paragraphs)));
            paragraphs.clear();
        }
    }

    private void pack(Section section, List<Chunk> chunks) {
        var box = new Box(section, chunks);
        for (var paragraph : section.paragraphs()) {
            if (paragraph.startsWith(TABLE_HEADER) && TokenEstimator.estimate(paragraph) > maxTokens) {
                box.flush();
                rowGroups(paragraph).forEach(group -> emit(section, group, chunks));
            } else {
                pieces(paragraph).forEach(box::add);
            }
        }
        box.flush();
    }

    /** 一个章节正在装的分块：装不下时写出已装的，下一块从它末尾的重叠开始。 */
    private final class Box {

        private final Section section;
        private final List<Chunk> chunks;
        private final StringBuilder buffer = new StringBuilder();
        private boolean hasNewContent;

        Box(Section section, List<Chunk> chunks) {
            this.section = section;
            this.chunks = chunks;
        }

        void add(String piece) {
            var candidate = buffer.isEmpty() ? piece : buffer + "\n" + piece;
            if (hasNewContent && TokenEstimator.estimate(candidate) > maxTokens) {
                var overlap = tail(buffer.toString());
                emit(section, buffer.toString(), chunks);
                candidate = overlap.isEmpty() ? piece : overlap + "\n" + piece;
            }
            buffer.setLength(0);
            buffer.append(candidate);
            hasNewContent = true;
        }

        /** 写出已装的内容，下一块从空开始：大表格的行组前后不带重叠。 */
        void flush() {
            if (hasNewContent) {
                emit(section, buffer.toString(), chunks);
            }
            buffer.setLength(0);
            hasNewContent = false;
        }
    }

    /** 把一段拆成不超过上限的片段：先按行，再按句子，最后按字符。 */
    private List<String> pieces(String paragraph) {
        if (TokenEstimator.estimate(paragraph) <= maxTokens) {
            return List.of(paragraph);
        }
        var result = new ArrayList<String>();
        for (var line : paragraph.split("\n")) {
            if (TokenEstimator.estimate(line) <= maxTokens) {
                result.add(line);
                continue;
            }
            for (var sentence : SENTENCE_END.split(line)) {
                if (sentence.isBlank()) {
                    continue;
                }
                result.addAll(TokenEstimator.estimate(sentence) <= maxTokens ? List.of(sentence.strip())
                        : hardSplit(sentence.strip(), maxTokens));
            }
        }
        return result;
    }

    /**
     * 大表格按整行装成行组，每组以表头行开头，组与组之间不重叠（每行本身带着列名）。
     * 单独一行就超过上限的按字符切开，每段也带上表头。
     */
    private List<String> rowGroups(String table) {
        var lines = table.split("\n");
        var header = lines[0];
        int budget = Math.max(maxTokens / 2, maxTokens - TokenEstimator.estimate(header));
        var groups = new ArrayList<String>();
        var group = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            var row = lines[i];
            for (var piece : TokenEstimator.estimate(row) <= budget ? List.of(row) : hardSplit(row, budget)) {
                if (!group.isEmpty() && TokenEstimator.estimate(group + "\n" + piece) > budget) {
                    groups.add(header + "\n" + group);
                    group.setLength(0);
                }
                group.append(group.isEmpty() ? "" : "\n").append(piece);
            }
        }
        if (!group.isEmpty()) {
            groups.add(header + "\n" + group);
        }
        return groups;
    }

    private static List<String> hardSplit(String text, int limit) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            current.appendCodePoint(codePoint);
            if (TokenEstimator.estimate(current) >= limit) {
                result.add(current.toString());
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 取上一块末尾约 overlapTokens 个 token 的文字，作为下一块的开头；
     * 其中有换行或句末标点时从下一行、下一句开始，不从半句开始。
     */
    private String tail(String text) {
        if (overlapTokens == 0) {
            return "";
        }
        int start = text.length();
        while (start > 0) {
            int previous = text.offsetByCodePoints(start, -1);
            if (TokenEstimator.estimate(text.substring(previous)) > overlapTokens) {
                break;
            }
            start = previous;
        }
        var overlap = text.substring(start);
        if (start == 0 || text.charAt(start - 1) == '\n') {
            return overlap.strip();
        }
        int line = overlap.indexOf('\n');
        if (line >= 0) {
            return overlap.substring(line + 1).strip();
        }
        var sentence = SENTENCE_BOUNDARY.matcher(overlap);
        return (sentence.find() && sentence.end() < overlap.length() ? overlap.substring(sentence.end()) : overlap)
                .strip();
    }

    private static void emit(Section section, String content, List<Chunk> chunks) {
        var trimmed = content.strip();
        if (!trimmed.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), section.headingPath(), trimmed, TokenEstimator.estimate(trimmed)));
        }
    }
}
