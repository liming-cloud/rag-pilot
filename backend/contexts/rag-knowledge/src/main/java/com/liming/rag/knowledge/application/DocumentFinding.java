package com.liming.rag.knowledge.application;

import java.util.List;

/**
 * 资料体检发现的一个问题（#218、D-070）：只提示，不影响入库和检索。规则写在 KnowledgeQueryMapper.xml 的
 * {@code findings}，deploy/parse-check.sql 用同样的规则和名称。
 *
 * @param similar 重复、疑似重复时的另一份文档，只列查看人能访问的库；其他问题为空
 */
public record DocumentFinding(Code code, List<SimilarDocument> similar) {

    public DocumentFinding {
        similar = List.copyOf(similar);
    }

    /** 问题种类，前端按它显示名称和整改建议。 */
    public enum Code {
        IMAGE_HEAVY, WORD_NO_HEADINGS, PDF_NO_STRUCTURE, NO_HEADINGS, LARGE_TABLE, GARBLED, SCATTERED, NO_CHUNKS,
        DUPLICATE, NEAR_DUPLICATE
    }

    /** 内容相同或大部分重合的另一份文档。 */
    public record SimilarDocument(long knowledgeBaseId, String knowledgeBaseName, long documentId, String filename) {
    }

    /** 文档列表按体检结论筛选：需整理（至少一个问题）或正常。 */
    public enum Filter {
        NEEDS_WORK, OK
    }
}
