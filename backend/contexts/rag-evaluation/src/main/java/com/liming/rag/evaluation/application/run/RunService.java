package com.liming.rag.evaluation.application.run;

import com.liming.rag.evaluation.application.ComparedRun.Compared;
import com.liming.rag.evaluation.application.DocumentStates;
import com.liming.rag.evaluation.application.EvalSet;
import com.liming.rag.evaluation.application.EvaluationException;
import com.liming.rag.evaluation.application.ExpectedDocument;
import com.liming.rag.evaluation.application.GoldenQuestion;
import com.liming.rag.evaluation.application.RunComparisons;
import com.liming.rag.evaluation.application.RunStore;
import com.liming.rag.evaluation.application.RunSummary;
import com.liming.rag.evaluation.application.StoredRun;
import com.liming.rag.evaluation.application.SuiteStore;
import com.liming.rag.evaluation.application.Viewers;
import com.liming.rag.evaluation.application.Viewers.Viewer;
import com.liming.rag.settings.api.ModelCosts;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行题集（#224）：运行前给出题数和预估费用；开始后在后台逐题运行（{@link RunWorker}），只跑运行人能访问的库里、
 * 没有过期的题。结果和上一次运行对比，可导出；查看人只看到自己能访问的库里的题。
 */
@Service
public class RunService {

    static final String CURRENCY = "CNY";

    private final SuiteStore suites;
    private final RunStore runs;
    private final Viewers viewers;
    private final DocumentStates states;
    private final RunComparisons comparisons;
    private final ModelCosts costs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RunService(SuiteStore suites, RunStore runs, Viewers viewers, DocumentStates states, RunComparisons comparisons,
            ModelCosts costs, ApplicationEventPublisher events, Clock clock) {
        this.suites = suites;
        this.runs = runs;
        this.viewers = viewers;
        this.states = states;
        this.comparisons = comparisons;
        this.costs = costs;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RunEstimate estimate(TenantId tenantId, UserId runner, long setId) {
        var visible = visible(viewers.of(tenantId, runner), setId);
        var runnable = runnable(tenantId, visible);
        var cost = costs.averageAnswerCost()
                .map(each -> each.multiply(BigDecimal.valueOf(runnable.size())).setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
        return new RunEstimate(runnable.size(), visible.size() - runnable.size(), cost, CURRENCY);
    }

    @Transactional
    public RunSummary start(TenantId tenantId, UserId runner, long setId) {
        var viewer = viewers.of(tenantId, runner);
        var questions = runnable(tenantId, visible(viewer, setId));
        if (questions.isEmpty()) {
            throw EvaluationException.invalid("这个题集里没有你能运行的题（无权访问的库里的题和过期题不参与运行）");
        }
        var run = runs.start(tenantId, setId, runner, questions, OffsetDateTime.now(clock))
                .orElseThrow(() -> new EvaluationException(EvaluationException.Reason.RUNNING, "这个题集正在运行，等它跑完再试"));
        events.publishEvent(new RunStarted(tenantId, runner, run.id()));
        return comparisons.compare(viewer, run).summary();
    }

    @Transactional(readOnly = true)
    public List<RunSummary> runs(TenantId tenantId, UserId viewerId, long setId) {
        var viewer = viewers.of(tenantId, viewerId);
        set(tenantId, setId);
        return runs.runs(tenantId, setId).stream().map(run -> comparisons.compare(viewer, run).summary()).toList();
    }

    @Transactional(readOnly = true)
    public RunDetail run(TenantId tenantId, UserId viewerId, long runId) {
        var viewer = viewers.of(tenantId, viewerId);
        var run = find(tenantId, runId);
        var set = set(tenantId, run.setId());
        var compared = comparisons.compare(viewer, run);
        var s = compared.summary();
        var results = compared.results().stream().map(result -> view(viewer, result)).filter(Objects::nonNull).toList();
        return new RunDetail(s.id(), s.status(), s.total(), s.done(), s.passed(), s.failed(), s.newlyFailed(),
                s.newlyPassed(), s.runnerName(), s.startedAt(), s.finishedAt(), set.id(), set.name(), results);
    }

    /** 导出运行结果（UTF-8 带 BOM 的 CSV），只含查看人能看到的、已跑完的题。 */
    @Transactional(readOnly = true)
    public RunExport export(TenantId tenantId, UserId viewerId, long runId) {
        var detail = run(tenantId, viewerId, runId);
        return new RunExport("eval-run-" + runId + ".csv", RunCsv.write(detail.results()));
    }

    private List<GoldenQuestion> visible(Viewer viewer, long setId) {
        set(viewer.tenantId(), setId);
        return suites.questions(viewer.tenantId(), setId).stream().filter(viewer::sees).toList();
    }

    private List<GoldenQuestion> runnable(TenantId tenantId, List<GoldenQuestion> questions) {
        var existing = states.of(tenantId, questions);
        return questions.stream().filter(question -> !existing.stale(question)).toList();
    }

    private EvalSet set(TenantId tenantId, long setId) {
        return suites.set(tenantId, setId).orElseThrow(() -> EvaluationException.notFound("题集"));
    }

    private StoredRun find(TenantId tenantId, long runId) {
        return runs.run(tenantId, runId).orElseThrow(() -> EvaluationException.notFound("运行记录"));
    }

    /** 跑完的题转成结果行；还没跑到的不列。 */
    private static @Nullable ResultView view(Viewer viewer, Compared compared) {
        var question = compared.result().question();
        var outcome = compared.result().outcome();
        if (outcome == null || compared.change() == null) {
            return null;
        }
        var expected = question.expected();
        var missing = expected.stream().filter(document -> !outcome.recalledDocumentIds().contains(document.documentId()))
                .map(ExpectedDocument::filename).toList();
        return new ResultView(question.id(), question.question(), viewer.knowledgeBaseName(question.knowledgeBaseId()),
                question.expectation(), question.match(), outcome.passed(), compared.change(), outcome.refused(),
                expected.size(), expected.size() - missing.size(), count(expected, outcome.citedDocumentIds()), missing,
                outcome.answerText(), outcome.citedDocuments());
    }

    private static int count(List<ExpectedDocument> expected, List<Long> found) {
        return (int) expected.stream().filter(document -> found.contains(document.documentId())).count();
    }
}
