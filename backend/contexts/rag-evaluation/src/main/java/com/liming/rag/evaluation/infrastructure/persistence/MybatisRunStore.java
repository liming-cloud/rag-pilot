package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.ExpectedDocument;
import com.liming.rag.evaluation.application.GoldenQuestion;
import com.liming.rag.evaluation.application.MatchMode;
import com.liming.rag.evaluation.application.QuestionOutcome;
import com.liming.rag.evaluation.application.RunStatus;
import com.liming.rag.evaluation.application.RunStore;
import com.liming.rag.evaluation.application.StoredResult;
import com.liming.rag.evaluation.application.StoredRun;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class MybatisRunStore implements RunStore {

    private final EvalRunMapper runs;
    private final EvalResultMapper results;

    MybatisRunStore(EvalRunMapper runs, EvalResultMapper results) {
        this.runs = runs;
        this.results = results;
    }

    @Override
    public Optional<StoredRun> start(TenantId tenantId, long setId, UserId runner, List<GoldenQuestion> questions,
            OffsetDateTime now) {
        if (runs.exists(new LambdaQueryWrapper<EvalRunDO>().eq(EvalRunDO::getSetId, setId)
                .eq(EvalRunDO::getStatus, RunStatus.RUNNING.name()))) {
            return Optional.empty();
        }
        var row = new EvalRunDO();
        row.setTenantId(tenantId.value());
        row.setSetId(setId);
        row.setRunnerId(runner.value());
        row.setStatus(RunStatus.RUNNING.name());
        row.setStartedAt(now);
        try {
            runs.insert(row);
        } catch (DuplicateKeyException e) {
            // 两个人同时点“运行”：唯一索引只放进一个。
            return Optional.empty();
        }
        results.insertAll(IntStream.range(0, questions.size())
                .mapToObj(index -> planned(row.getId(), index, questions.get(index))).toList());
        return Optional.of(toRun(row));
    }

    @Override
    public void saveOutcome(long runId, long questionId, QuestionOutcome outcome) {
        var row = new EvalResultDO();
        row.setRunId(runId);
        row.setQuestionId(questionId);
        row.setPassed(outcome.passed());
        row.setRefused(outcome.refused());
        row.setRecalledDocumentIds(outcome.recalledDocumentIds().toArray(Long[]::new));
        row.setCitedDocumentIds(outcome.citedDocumentIds().toArray(Long[]::new));
        row.setCitedDocuments(outcome.citedDocuments().toArray(String[]::new));
        row.setAnswerText(outcome.answerText());
        results.saveOutcome(row);
    }

    @Override
    public void finish(long runId, RunStatus status, OffsetDateTime now) {
        runs.update(new LambdaUpdateWrapper<EvalRunDO>().set(EvalRunDO::getStatus, status.name())
                .set(EvalRunDO::getFinishedAt, now).eq(EvalRunDO::getId, runId)
                .eq(EvalRunDO::getStatus, RunStatus.RUNNING.name()));
    }

    @Override
    public int failUnfinished(OffsetDateTime now) {
        return runs.update(new LambdaUpdateWrapper<EvalRunDO>().set(EvalRunDO::getStatus, RunStatus.FAILED.name())
                .set(EvalRunDO::getFinishedAt, now).eq(EvalRunDO::getStatus, RunStatus.RUNNING.name()));
    }

    @Override
    public Optional<StoredRun> run(TenantId tenantId, long runId) {
        return Optional.ofNullable(runs.selectOne(new LambdaQueryWrapper<EvalRunDO>()
                .eq(EvalRunDO::getTenantId, tenantId.value()).eq(EvalRunDO::getId, runId))).map(MybatisRunStore::toRun);
    }

    @Override
    public List<StoredRun> runs(TenantId tenantId, long setId) {
        return runs.selectList(new LambdaQueryWrapper<EvalRunDO>().eq(EvalRunDO::getTenantId, tenantId.value())
                .eq(EvalRunDO::getSetId, setId).orderByDesc(EvalRunDO::getId))
                .stream().map(MybatisRunStore::toRun).toList();
    }

    @Override
    public Map<Long, StoredRun> latestRuns(TenantId tenantId) {
        return runs.latest(tenantId.value()).stream().map(MybatisRunStore::toRun)
                .collect(Collectors.toMap(StoredRun::setId, Function.identity()));
    }

    @Override
    public Optional<StoredRun> previous(StoredRun run) {
        return runs.selectList(new LambdaQueryWrapper<EvalRunDO>().eq(EvalRunDO::getSetId, run.setId())
                        .lt(EvalRunDO::getId, run.id()).orderByDesc(EvalRunDO::getId))
                .stream().findFirst().map(MybatisRunStore::toRun);
    }

    @Override
    public List<StoredResult> results(long runId) {
        return results.findByRun(runId).stream().map(MybatisRunStore::toResult).toList();
    }

    private static EvalResultDO planned(long runId, int position, GoldenQuestion question) {
        var row = new EvalResultDO();
        row.setRunId(runId);
        row.setQuestionId(question.id());
        row.setPosition(position);
        row.setQuestion(question.question());
        row.setKnowledgeBaseId(question.knowledgeBaseId());
        row.setExpectation(question.expectation().name());
        row.setMatchMode(question.match().name());
        row.setExpectedDocumentIds(question.expected().stream().map(ExpectedDocument::documentId).toArray(Long[]::new));
        row.setExpectedDocuments(question.expected().stream().map(ExpectedDocument::filename).toArray(String[]::new));
        return row;
    }

    private static StoredRun toRun(EvalRunDO row) {
        return new StoredRun(row.getId(), row.getSetId(), row.getRunnerId(), RunStatus.valueOf(row.getStatus()),
                row.getStartedAt(), row.getFinishedAt());
    }

    private static StoredResult toResult(EvalResultDO row) {
        var ids = row.getExpectedDocumentIds();
        var names = row.getExpectedDocuments();
        var expected = new ArrayList<ExpectedDocument>();
        for (int i = 0; i < ids.length; i++) {
            expected.add(new ExpectedDocument(ids[i], names[i]));
        }
        var question = new GoldenQuestion(row.getQuestionId(), 0, row.getQuestion(), row.getKnowledgeBaseId(),
                Expectation.valueOf(row.getExpectation()), MatchMode.valueOf(row.getMatchMode()), expected,
                OffsetDateTime.MIN);
        return new StoredResult(question, row.getPassed() == null ? null : outcome(row));
    }

    private static QuestionOutcome outcome(EvalResultDO row) {
        return new QuestionOutcome(row.getPassed(), Boolean.TRUE.equals(row.getRefused()),
                Arrays.asList(row.getRecalledDocumentIds()), Arrays.asList(row.getCitedDocumentIds()),
                Arrays.asList(row.getCitedDocuments()), row.getAnswerText() == null ? "" : row.getAnswerText());
    }
}
