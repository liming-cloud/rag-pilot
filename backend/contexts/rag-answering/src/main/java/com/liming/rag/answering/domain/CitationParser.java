package com.liming.rag.answering.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 校验模型回答里的引用标记 [n]：只保留指向真实资料（1..资料条数）的标记，其余删掉。
 *
 * <p>引用是“可核对”的唯一依据，所以模型编出来的序号一律不展示。模型偶尔会写成全角括号、【1】、
 * [资料1]、[1,2] 这类变体，先统一成 [1][2] 再校验。
 */
public final class CitationParser {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})\\]");
    private static final Pattern VARIANT = Pattern.compile(
            "[\\[［【〔]\\s*(?:资料|来源|证据|参考)?\\s*((?:\\d{1,3}\\s*[,，、]\\s*)*\\d{1,3})\\s*[\\]］】〕]");

    private CitationParser() {
    }

    /**
     * @param text 清理后的回答（删掉了无效标记）
     * @param cited 有效引用序号，按首次出现排序
     */
    public record Parsed(String text, List<Integer> cited) {
    }

    public static Parsed parse(String answer, int evidenceCount) {
        var cited = new LinkedHashSet<Integer>();
        var matcher = MARKER.matcher(normalize(answer));
        var cleaned = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            boolean valid = index >= 1 && index <= evidenceCount;
            if (valid) {
                cited.add(index);
            }
            matcher.appendReplacement(cleaned, valid ? "[" + index + "]" : "");
        }
        matcher.appendTail(cleaned);
        return new Parsed(cleaned.toString().strip(), List.copyOf(cited));
    }

    private static String normalize(String answer) {
        var matcher = VARIANT.matcher(answer);
        var text = new StringBuilder();
        while (matcher.find()) {
            var numbers = matcher.group(1).split("\\s*[,，、]\\s*");
            var markers = new StringBuilder();
            for (var number : numbers) {
                markers.append('[').append(number.strip()).append(']');
            }
            matcher.appendReplacement(text, markers.toString());
        }
        matcher.appendTail(text);
        return text.toString();
    }
}
