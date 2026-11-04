package com.liming.rag.answering.application.ask;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 拼证据步骤：把证据按顺序编号成“[n] 来源：文档 / 章节”后接正文，最后附上问题。编号就是回答里 [n] 引用的序号；
 * 正文行首的列表序号去掉，只留资料编号这一种数字编号。
 * 会话里有前几轮对话时，放在资料之后、问题之前，并写明只用来理解问题（D-036）。
 * 格式与 rag-ai 的本地对话实现约定一致，改动时一起改。
 */
final class EvidencePrompt {

    /** 行首的列表序号（“1.”“2、”“3）”）。模型会把它当成引用序号写成 [2]（#71、#107），送给模型前去掉。 */
    private static final Pattern LIST_NUMBER = Pattern.compile("(?m)^[ \\t]*\\d{1,2}[.、．)）][ \\t]*");

    private EvidencePrompt() {
    }

    static String user(String question, List<Evidence> evidence, String history) {
        var text = new StringBuilder("资料：\n");
        for (int i = 0; i < evidence.size(); i++) {
            var item = evidence.get(i);
            text.append('[').append(i + 1).append("] 来源：").append(item.documentName());
            if (!item.headingPath().isBlank()) {
                text.append(" / ").append(item.headingPath());
            }
            text.append('\n').append(LIST_NUMBER.matcher(item.text().strip()).replaceAll("")).append("\n\n");
        }
        if (history != null && !history.isBlank()) {
            text.append("前面的对话（只用来理解当前问题指的是什么，回答的依据只能是上面的资料）：\n")
                    .append(history.strip()).append("\n\n");
        }
        return text.append("问题：").append(question).toString();
    }
}
