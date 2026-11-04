package com.liming.rag.knowledge.domain;

/**
 * 估算文本的 token 数，用于控制分块大小。
 *
 * <p>只求量级准确，不追求和具体模型的分词器一致：汉字和全角标点按 1 个 token，
 * 其他非空白字符按每 4 个 1 个 token。换模型不需要改这里，分块参数由评测集调。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(CharSequence text) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    static boolean isCjk(int codePoint) {
        var script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN) {
            return true;
        }
        var block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
