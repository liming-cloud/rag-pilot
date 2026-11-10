package com.liming.rag.answering.domain;

/**
 * 员工的一个问题（#136）：去掉首尾空格后不能为空，不超过 {@value #MAX_LENGTH} 个字（与数据库列宽一致）。
 * 直接提问、会话提问都按这里判断；模型给出的检索词、改写后的问题也不能超过这个长度。
 */
public record Question(String text) {

    public static final int MAX_LENGTH = 500;

    /** @throws AnsweringRuleException 为空或太长 */
    public Question {
        text = text == null ? "" : text.strip();
        if (!fits(text)) {
            throw AnsweringRuleException.invalid("问题不能为空，且不超过 " + MAX_LENGTH + " 个字");
        }
    }

    /** 去掉首尾空格后的这段文字能不能当问题（或检索词）用。 */
    public static boolean fits(String text) {
        var value = text == null ? "" : text.strip();
        return !value.isEmpty() && value.length() <= MAX_LENGTH;
    }
}
