package com.liming.rag.knowledge.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** 文档列表里重复、疑似重复的另一份文档（#218），只在 infrastructure 包内读取。 */
@NullUnmarked
final class SimilarDocumentDO {

    Long documentId;
    String code;
    Long knowledgeBaseId;
    String knowledgeBaseName;
    Long otherDocumentId;
    String filename;
}
