package com.liming.rag.knowledge.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按标题感知的文本分块：先按 Markdown 标题切成章节，再在章节内按段落装箱。
 *
 * <p>规则：分块不跨章节；单块目标不超过 {@link #MAX_TOKENS}；同一章节的相邻分块
 * 重叠约 {@link #OVERLAP_TOKENS} 个 token，避免答案正好落在切口上；超长段落先按句子切，
 * 再按字符切。算法是确定性的，同样的输入总是得到同样的分块。
 */
public final class TextChunker {

    public static final int MAX_TOKENS = 500;
    public static final int OVERLAP_TOKENS = 80;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;])|(?<=\\.\\s)");

    private final int maxTokens;
    private final int overlapTokens;

    public TextChunker() {
        this(MAX_TOKENS, OVERLAP_TOKENS);
    }

    TextChunker(int maxTokens, int overlapTokens) {
        if (maxTokens <= 0 || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("分块参数不合法");
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
        var headings = new ArrayList<String>();
        var levels = new ArrayList<Integer>();
        var paragraphs = new ArrayList<String>();
        var paragraph = new StringBuilder();
        String path = title;
        for (var line : text.split("\n", -1)) {
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(paragraph, paragraphs);
                addSection(result, path, paragraphs);
                int level = heading.group(1).length();
                while (!levels.isEmpty() && levels.getLast() >= level) {
                    levels.removeLast();
                    headings.removeLast();
                }
                levels.add(level);
                headings.add(heading.group(2).strip());
                path = title + " > " + String.join(" > ", headings);
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
        var buffer = new StringBuilder();
        boolean hasNewContent = false;
        for (var piece : pieces(section.paragraphs())) {
            var candidate = buffer.isEmpty() ? piece : buffer + "\n" + piece;
            if (hasNewContent && TokenEstimator.estimate(candidate) > maxTokens) {
                emit(section, buffer.toString(), chunks);
                var overlap = tail(buffer.toString());
                buffer.setLength(0);
                buffer.append(overlap);
                candidate = buffer.isEmpty() ? piece : buffer + "\n" + piece;
            }
            buffer.setLength(0);
            buffer.append(candidate);
            hasNewContent = true;
        }
        if (hasNewContent) {
            emit(section, buffer.toString(), chunks);
        }
    }

    /** 把章节内的段落拆成不超过上限的片段：先按句子，再按字符。 */
    private List<String> pieces(List<String> paragraphs) {
        var result = new ArrayList<String>();
        for (var paragraph : paragraphs) {
            if (TokenEstimator.estimate(paragraph) <= maxTokens) {
                result.add(paragraph);
                continue;
            }
            for (var sentence : SENTENCE_END.split(paragraph)) {
                if (sentence.isBlank()) {
                    continue;
                }
                if (TokenEstimator.estimate(sentence) <= maxTokens) {
                    result.add(sentence.strip());
                } else {
                    result.addAll(hardSplit(sentence.strip()));
                }
            }
        }
        return result;
    }

    private List<String> hardSplit(String text) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            current.appendCodePoint(codePoint);
            if (TokenEstimator.estimate(current) >= maxTokens) {
                result.add(current.toString());
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    /** 取上一块末尾约 overlapTokens 个 token 的文字，作为下一块的开头。 */
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
        return text.substring(start).strip();
    }

    private static void emit(Section section, String content, List<Chunk> chunks) {
        var trimmed = content.strip();
        if (!trimmed.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), section.headingPath(), trimmed, TokenEstimator.estimate(trimmed)));
        }
    }
}
