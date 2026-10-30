package com.liming.rag.answering.application;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;

/**
 * 拼证据步骤：把证据按顺序编号成“[n] 来源：文档 / 章节”后接正文，最后附上问题。编号就是回答里 [n] 引用的序号。
 * 会话里有前几轮对话时（{@link AnswerContext#HISTORY}），放在资料之后、问题之前，并写明只用来理解问题（D-036）。
 * 格式与 rag-ai 的本地对话实现约定一致，改动时一起改。
 */
final class NumberedEvidenceAugmenter implements QueryAugmenter {

    @Override
    public Query augment(Query query, List<Document> evidence) {
        var text = new StringBuilder("资料：\n");
        for (int i = 0; i < evidence.size(); i++) {
            var document = evidence.get(i);
            text.append('[').append(i + 1).append("] 来源：").append(Evidence.documentName(document));
            var headingPath = Evidence.headingPath(document);
            if (headingPath != null && !headingPath.isBlank()) {
                text.append(" / ").append(headingPath);
            }
            text.append('\n').append(document.getText().strip()).append("\n\n");
        }
        if (query.context().get(AnswerContext.HISTORY) instanceof String history && !history.isBlank()) {
            text.append("前面的对话（只用来理解当前问题指的是什么，回答的依据只能是上面的资料）：\n")
                    .append(history.strip()).append("\n\n");
        }
        return query.mutate().text(text.append("问题：").append(query.text()).toString()).build();
    }
}
