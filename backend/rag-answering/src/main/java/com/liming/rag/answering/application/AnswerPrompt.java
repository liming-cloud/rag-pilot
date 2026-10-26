package com.liming.rag.answering.application;

import com.liming.rag.knowledge.api.SearchHit;
import java.util.List;

/** 回答提示词。资料格式（“[n] 来源：…”后接正文）与 rag-ai 的本地对话实现约定一致，改动时一起改。 */
final class AnswerPrompt {

    static final String NO_ANSWER = "NO_ANSWER";

    static final String SYSTEM = """
            你是连锁门店的运营助手，回答门店员工的问题。
            规则：
            1. 只能依据下面给出的资料回答，不要使用资料以外的知识，不要猜测。
            2. 每句话末尾用 [序号] 标注依据的资料，例如“临期商品提前三天下架[2]。”；一句话依据多条资料时写成 [1][3]。
            3. 回答简洁、直接，先给结论；涉及步骤时用编号列出。
            4. 如果资料不足以回答，只回复 NO_ANSWER，不要输出其他内容。
            """;

    private AnswerPrompt() {
    }

    static String user(String question, List<SearchHit> evidence) {
        var text = new StringBuilder("资料：\n");
        for (int i = 0; i < evidence.size(); i++) {
            var hit = evidence.get(i);
            text.append('[').append(i + 1).append("] 来源：").append(hit.documentName());
            if (hit.headingPath() != null && !hit.headingPath().isBlank()) {
                text.append(" / ").append(hit.headingPath());
            }
            text.append('\n').append(hit.content().strip()).append("\n\n");
        }
        return text.append("问题：").append(question).toString();
    }
}
