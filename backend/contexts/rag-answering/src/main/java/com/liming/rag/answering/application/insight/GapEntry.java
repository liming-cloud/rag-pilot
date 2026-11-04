package com.liming.rag.answering.application.insight;

import java.time.OffsetDateTime;
import java.util.List;

/** 类里的一条原始记录；{@code knowledgeBases} 是当时检索的知识库，旧记录为空。 */
public record GapEntry(long id, String question, String askerName, OffsetDateTime createdAt, boolean refused,
        String comment, List<String> knowledgeBases, List<String> citedDocuments, String reviewStatus) {
}
