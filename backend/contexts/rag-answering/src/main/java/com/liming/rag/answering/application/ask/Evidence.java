package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.domain.Citation;
import com.liming.rag.knowledge.api.SearchHit;

/**
 * 一条证据：检索到的分块和它当前的分数（检索阶段是检索得分，重排后是重排分数）。问答链路只用这个类型（#145），
 * 不用框架的文档类型，上下文不依赖 Spring AI（规则卡 A-07、A-09）。对外仍是 {@link Citation}。
 */
record Evidence(long chunkId, long documentId, String documentName, String headingPath, String text, double score) {

    Evidence {
        headingPath = headingPath == null ? "" : headingPath;
    }

    static Evidence of(SearchHit hit) {
        return new Evidence(hit.chunkId(), hit.documentId(), hit.documentName(), hit.headingPath(), hit.content(),
                hit.score());
    }

    Evidence withScore(double newScore) {
        return new Evidence(chunkId, documentId, documentName, headingPath, text, newScore);
    }

    Citation citation(int index) {
        return new Citation(index, chunkId, documentId, documentName, headingPath, text);
    }
}
