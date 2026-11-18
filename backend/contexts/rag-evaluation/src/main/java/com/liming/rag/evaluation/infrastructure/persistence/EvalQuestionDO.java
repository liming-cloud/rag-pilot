package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** eval_question 的一行。 */
@TableName("eval_question")
@NullUnmarked
final class EvalQuestionDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long setId;
    private String question;
    private Long knowledgeBaseId;
    private String expectation;
    private String matchMode;
    private OffsetDateTime createdAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    Long getTenantId() {
        return tenantId;
    }

    void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    Long getSetId() {
        return setId;
    }

    void setSetId(Long setId) {
        this.setId = setId;
    }

    String getQuestion() {
        return question;
    }

    void setQuestion(String question) {
        this.question = question;
    }

    Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    String getExpectation() {
        return expectation;
    }

    void setExpectation(String expectation) {
        this.expectation = expectation;
    }

    String getMatchMode() {
        return matchMode;
    }

    void setMatchMode(String matchMode) {
        this.matchMode = matchMode;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
