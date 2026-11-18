package com.liming.rag.evaluation.application.suite;

import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.MatchMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 题集里的一道标准题。
 *
 * @param stale 有期望文档已删除：不参与运行，改好期望文档后恢复
 */
public record QuestionView(long id, String question, long knowledgeBaseId, String knowledgeBaseName,
        Expectation expectation, MatchMode match, List<ExpectedDocumentView> expectedDocuments, boolean stale,
        OffsetDateTime createdAt) {

    /** 期望文档：当前文件名（已删除时是保存时的文件名）和是否已删除。 */
    public record ExpectedDocumentView(long documentId, String filename, boolean deleted) {
    }
}
