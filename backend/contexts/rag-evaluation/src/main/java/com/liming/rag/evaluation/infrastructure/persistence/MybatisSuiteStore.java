package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liming.rag.evaluation.application.EvalSet;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.ExpectedDocument;
import com.liming.rag.evaluation.application.GoldenQuestion;
import com.liming.rag.evaluation.application.MatchMode;
import com.liming.rag.evaluation.application.QuestionDraft;
import com.liming.rag.evaluation.application.SuiteStore;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
class MybatisSuiteStore implements SuiteStore {

    private final EvalSetMapper sets;
    private final EvalQuestionMapper questions;
    private final EvalExpectedDocumentMapper expected;

    MybatisSuiteStore(EvalSetMapper sets, EvalQuestionMapper questions, EvalExpectedDocumentMapper expected) {
        this.sets = sets;
        this.questions = questions;
        this.expected = expected;
    }

    @Override
    public List<EvalSet> sets(TenantId tenantId) {
        return sets.selectList(new LambdaQueryWrapper<EvalSetDO>().eq(EvalSetDO::getTenantId, tenantId.value())
                .orderByDesc(EvalSetDO::getUpdatedAt).orderByDesc(EvalSetDO::getId))
                .stream().map(MybatisSuiteStore::toSet).toList();
    }

    @Override
    public Optional<EvalSet> set(TenantId tenantId, long setId) {
        return Optional.ofNullable(sets.selectOne(new LambdaQueryWrapper<EvalSetDO>()
                .eq(EvalSetDO::getTenantId, tenantId.value()).eq(EvalSetDO::getId, setId))).map(MybatisSuiteStore::toSet);
    }

    @Override
    public EvalSet createSet(TenantId tenantId, UserId creator, String name, String description, OffsetDateTime now) {
        var row = new EvalSetDO();
        row.setTenantId(tenantId.value());
        row.setName(name);
        row.setDescription(description);
        row.setCreatedBy(creator.value());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        sets.insert(row);
        return toSet(row);
    }

    @Override
    public void updateSet(TenantId tenantId, long setId, String name, String description, OffsetDateTime now) {
        sets.update(new LambdaUpdateWrapper<EvalSetDO>().set(EvalSetDO::getName, name)
                .set(EvalSetDO::getDescription, description).set(EvalSetDO::getUpdatedAt, now)
                .eq(EvalSetDO::getTenantId, tenantId.value()).eq(EvalSetDO::getId, setId));
    }

    @Override
    public void touchSet(TenantId tenantId, long setId, OffsetDateTime now) {
        sets.update(new LambdaUpdateWrapper<EvalSetDO>().set(EvalSetDO::getUpdatedAt, now)
                .eq(EvalSetDO::getTenantId, tenantId.value()).eq(EvalSetDO::getId, setId));
    }

    @Override
    public void deleteSet(TenantId tenantId, long setId) {
        sets.delete(new LambdaQueryWrapper<EvalSetDO>().eq(EvalSetDO::getTenantId, tenantId.value())
                .eq(EvalSetDO::getId, setId));
    }

    @Override
    public List<GoldenQuestion> questions(TenantId tenantId, @Nullable Long setId) {
        var rows = questions.selectList(new LambdaQueryWrapper<EvalQuestionDO>()
                .eq(EvalQuestionDO::getTenantId, tenantId.value()).eq(setId != null, EvalQuestionDO::getSetId, setId)
                .orderByAsc(EvalQuestionDO::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        var documents = expected.selectList(new LambdaQueryWrapper<EvalExpectedDocumentDO>()
                        .in(EvalExpectedDocumentDO::getQuestionId, rows.stream().map(EvalQuestionDO::getId).toList())
                        .orderByAsc(EvalExpectedDocumentDO::getDocumentId))
                .stream().collect(Collectors.groupingBy(EvalExpectedDocumentDO::getQuestionId));
        return rows.stream().map(row -> toQuestion(row, documents.getOrDefault(row.getId(), List.of()))).toList();
    }

    @Override
    public GoldenQuestion addQuestion(TenantId tenantId, long setId, QuestionDraft draft, OffsetDateTime now) {
        var row = new EvalQuestionDO();
        row.setTenantId(tenantId.value());
        row.setSetId(setId);
        row.setCreatedAt(now);
        fill(row, draft);
        questions.insert(row);
        insertExpected(tenantId, row.getId(), draft);
        return toQuestion(row, expected.selectList(byQuestion(row.getId())));
    }

    @Override
    public void updateQuestion(TenantId tenantId, long questionId, QuestionDraft draft) {
        var row = questions.selectOne(new LambdaQueryWrapper<EvalQuestionDO>()
                .eq(EvalQuestionDO::getTenantId, tenantId.value()).eq(EvalQuestionDO::getId, questionId));
        if (row == null) {
            return;
        }
        fill(row, draft);
        questions.updateById(row);
        expected.delete(new LambdaQueryWrapper<EvalExpectedDocumentDO>()
                .eq(EvalExpectedDocumentDO::getQuestionId, questionId));
        insertExpected(tenantId, questionId, draft);
    }

    @Override
    public void deleteQuestion(TenantId tenantId, long questionId) {
        questions.delete(new LambdaQueryWrapper<EvalQuestionDO>().eq(EvalQuestionDO::getTenantId, tenantId.value())
                .eq(EvalQuestionDO::getId, questionId));
    }

    @Override
    public int followReplacement(TenantId tenantId, long replacedDocumentId, long documentId) {
        int followed = expected.follow(tenantId.value(), replacedDocumentId, documentId);
        int merged = expected.delete(new LambdaQueryWrapper<EvalExpectedDocumentDO>()
                .eq(EvalExpectedDocumentDO::getTenantId, tenantId.value())
                .eq(EvalExpectedDocumentDO::getDocumentId, replacedDocumentId));
        return followed + merged;
    }

    private void insertExpected(TenantId tenantId, long questionId, QuestionDraft draft) {
        for (var document : draft.expected()) {
            var row = new EvalExpectedDocumentDO();
            row.setQuestionId(questionId);
            row.setTenantId(tenantId.value());
            row.setDocumentId(document.documentId());
            row.setFilename(document.filename());
            expected.insert(row);
        }
    }

    private static LambdaQueryWrapper<EvalExpectedDocumentDO> byQuestion(long questionId) {
        return new LambdaQueryWrapper<EvalExpectedDocumentDO>().eq(EvalExpectedDocumentDO::getQuestionId, questionId)
                .orderByAsc(EvalExpectedDocumentDO::getDocumentId);
    }

    private static void fill(EvalQuestionDO row, QuestionDraft draft) {
        row.setQuestion(draft.question());
        row.setKnowledgeBaseId(draft.knowledgeBaseId());
        row.setExpectation(draft.expectation().name());
        row.setMatchMode(draft.match().name());
    }

    private static EvalSet toSet(EvalSetDO row) {
        return new EvalSet(row.getId(), row.getName(), row.getDescription(), row.getUpdatedAt());
    }

    private static GoldenQuestion toQuestion(EvalQuestionDO row, List<EvalExpectedDocumentDO> documents) {
        return new GoldenQuestion(row.getId(), row.getSetId(), row.getQuestion(), row.getKnowledgeBaseId(),
                Expectation.valueOf(row.getExpectation()), MatchMode.valueOf(row.getMatchMode()),
                documents.stream().map(document -> new ExpectedDocument(document.getDocumentId(), document.getFilename()))
                        .toList(), row.getCreatedAt());
    }
}
