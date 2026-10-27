package com.liming.rag.answering.application;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;

/**
 * 拼证据步骤：把证据按顺序编号成“[n] 来源：文档 / 章节”后接正文，最后附上问题。编号就是回答里 [n] 引用的序号。
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
        return query.mutate().text(text.append("问题：").append(query.text()).toString()).build();
    }
}
