package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import org.jspecify.annotations.NullUnmarked;

/** eval_expected_document 的一行（主键是两列，没有 id）。 */
@TableName("eval_expected_document")
@NullUnmarked
final class EvalExpectedDocumentDO {

    private Long questionId;
    private Long tenantId;
    private Long documentId;
    private String filename;

    Long getQuestionId() {
        return questionId;
    }

    void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    Long getTenantId() {
        return tenantId;
    }

    void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    Long getDocumentId() {
        return documentId;
    }

    void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    String getFilename() {
        return filename;
    }

    void setFilename(String filename) {
        this.filename = filename;
    }
}
