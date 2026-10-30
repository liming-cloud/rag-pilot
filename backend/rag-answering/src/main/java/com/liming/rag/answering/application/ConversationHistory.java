package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Answer;
import java.util.ArrayDeque;
import java.util.List;

/**
 * 带进提示词的对话历史（D-036）：只取最近几轮，回答去掉引用标记、每条最多 {@value #MAX_ANSWER_CHARS} 字；
 * 拒答的轮次只带问题和一句说明，不带拒答话术，避免模型照着拒答；总长超出预算时丢最早的轮次。
 * 历史只用来理解当前问题指的是什么，回答的依据仍只能是这一轮检索到的资料。
 */
record ConversationHistory(String text) {

    static final int MAX_ANSWER_CHARS = 300;

    /**
     * @param answer 这一轮的回答；停止时没留下内容、或出错时为 null
     */
    record Entry(String question, Answer answer) {
    }

    static ConversationHistory empty() {
        return new ConversationHistory("");
    }

    /**
     * @param entries 主线上的轮次，按先后排列
     * @param turns 最多带最近几轮
     * @param maxChars 总长上限
     */
    static ConversationHistory of(List<Entry> entries, int turns, int maxChars) {
        var kept = new ArrayDeque<String>();
        int length = 0;
        for (int i = entries.size() - 1; i >= 0 && kept.size() < turns; i--) {
            var block = format(entries.get(i));
            int added = block.length() + (kept.isEmpty() ? 0 : 1);
            if (length + added > maxChars) {
                break;
            }
            kept.addFirst(block);
            length += added;
        }
        return new ConversationHistory(String.join("\n", kept));
    }

    boolean isEmpty() {
        return text.isEmpty();
    }

    private static String format(Entry entry) {
        return "员工：" + entry.question().strip() + "\n助手：" + reply(entry.answer());
    }

    private static String reply(Answer answer) {
        if (answer == null) {
            return "（这一轮没有结果）";
        }
        if (answer.refused()) {
            return "（这一轮没有找到依据）";
        }
        var text = answer.text().replaceAll("\\s*\\[\\d+]", "").strip();
        return text.length() > MAX_ANSWER_CHARS ? text.substring(0, MAX_ANSWER_CHARS) + "…" : text;
    }
}
