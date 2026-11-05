package com.liming.rag.answering.application.insight;

import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一类缺口。
 *
 * @param id 代表问题对应的最近一条记录，“重新回答”用它
 * @param question 代表问题：类里出现最多的原文
 * @param phrasings 不同的问题原文数
 * @param knowledgeBases 类里的记录当时检索过的知识库，出现多的在前；提示资料该补到哪个库
 * @param knowledgeBaseRefs 同上，带 id，用来直接打开这个库补资料
 * @param citedDocuments 差评记录当时引用的文档（拒答没有引用），提示该改哪份资料
 */
public record GapGroup(long id, String question, int count, int openCount, int refusedCount, int dislikedCount,
        int phrasings, OffsetDateTime lastAskedAt, List<String> knowledgeBases, List<KnowledgeBaseRef> knowledgeBaseRefs,
        List<String> citedDocuments,
        List<GapEntry> records) {
}
