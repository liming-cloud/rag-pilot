package com.liming.rag.evaluation.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** eval_result 的一行，数组列在 Mapper XML 里用 ArrayTypeHandler 读写。 */
@NullUnmarked
final class EvalResultDO {

    private Long runId;
    private Long questionId;
    private Integer position;
    private String question;
    private Long knowledgeBaseId;
    private String expectation;
    private String matchMode;
    private Long[] expectedDocumentIds;
    private String[] expectedDocuments;
    private Boolean passed;
    private Boolean refused;
    private Long[] recalledDocumentIds;
    private Long[] citedDocumentIds;
    private String[] citedDocuments;
    private String answerText;

    Long getRunId() {
        return runId;
    }

    void setRunId(Long runId) {
        this.runId = runId;
    }

    Long getQuestionId() {
        return questionId;
    }

    void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    Integer getPosition() {
        return position;
    }

    void setPosition(Integer position) {
        this.position = position;
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

    Long[] getExpectedDocumentIds() {
        return expectedDocumentIds;
    }

    void setExpectedDocumentIds(Long[] expectedDocumentIds) {
        this.expectedDocumentIds = expectedDocumentIds;
    }

    String[] getExpectedDocuments() {
        return expectedDocuments;
    }

    void setExpectedDocuments(String[] expectedDocuments) {
        this.expectedDocuments = expectedDocuments;
    }

    Boolean getPassed() {
        return passed;
    }

    void setPassed(Boolean passed) {
        this.passed = passed;
    }

    Boolean getRefused() {
        return refused;
    }

    void setRefused(Boolean refused) {
        this.refused = refused;
    }

    Long[] getRecalledDocumentIds() {
        return recalledDocumentIds;
    }

    void setRecalledDocumentIds(Long[] recalledDocumentIds) {
        this.recalledDocumentIds = recalledDocumentIds;
    }

    Long[] getCitedDocumentIds() {
        return citedDocumentIds;
    }

    void setCitedDocumentIds(Long[] citedDocumentIds) {
        this.citedDocumentIds = citedDocumentIds;
    }

    String[] getCitedDocuments() {
        return citedDocuments;
    }

    void setCitedDocuments(String[] citedDocuments) {
        this.citedDocuments = citedDocuments;
    }

    String getAnswerText() {
        return answerText;
    }

    void setAnswerText(String answerText) {
        this.answerText = answerText;
    }
}
