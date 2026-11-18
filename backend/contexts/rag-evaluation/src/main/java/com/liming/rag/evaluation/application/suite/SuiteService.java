package com.liming.rag.evaluation.application.suite;

import com.liming.rag.evaluation.application.DocumentStates;
import com.liming.rag.evaluation.application.DocumentStates.Existing;
import com.liming.rag.evaluation.application.EvalSet;
import com.liming.rag.evaluation.application.EvaluationException;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.ExpectedDocument;
import com.liming.rag.evaluation.application.GoldenQuestion;
import com.liming.rag.evaluation.application.QuestionDraft;
import com.liming.rag.evaluation.application.RunComparisons;
import com.liming.rag.evaluation.application.RunStore;
import com.liming.rag.evaluation.application.RunSummary;
import com.liming.rag.evaluation.application.SuiteStore;
import com.liming.rag.evaluation.application.Viewers;
import com.liming.rag.evaluation.application.Viewers.Viewer;
import com.liming.rag.knowledge.api.DocumentRef;
import com.liming.rag.knowledge.api.KnowledgeDocuments;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 题集和标准题（#224）：一道题在一个知识库里检索，期望命中这个库里的文档或期望拒答。
 * 查看人、操作人无权访问的库里的题当作不存在（D-070）：列表和计数不含，改、删时按“不存在”处理。
 */
@Service
public class SuiteService {

    static final int DOCUMENT_OPTIONS = 20;

    private final SuiteStore store;
    private final RunStore runs;
    private final Viewers viewers;
    private final DocumentStates states;
    private final RunComparisons comparisons;
    private final KnowledgeDocuments documents;
    private final Clock clock;

    SuiteService(SuiteStore store, RunStore runs, Viewers viewers, DocumentStates states, RunComparisons comparisons,
            KnowledgeDocuments documents, Clock clock) {
        this.store = store;
        this.runs = runs;
        this.viewers = viewers;
        this.states = states;
        this.comparisons = comparisons;
        this.documents = documents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SetView> sets(TenantId tenantId, UserId viewerId) {
        var viewer = viewers.of(tenantId, viewerId);
        var questions = store.questions(tenantId, null).stream().filter(viewer::sees).toList();
        var existing = states.of(tenantId, questions);
        var latest = runs.latestRuns(tenantId);
        return store.sets(tenantId).stream().map(set -> view(viewer, set,
                questions.stream().filter(question -> question.setId() == set.id()).toList(), existing,
                latest.containsKey(set.id()) ? comparisons.compare(viewer, latest.get(set.id())).summary() : null))
                .toList();
    }

    @Transactional(readOnly = true)
    public SetView set(TenantId tenantId, UserId viewerId, long setId) {
        var viewer = viewers.of(tenantId, viewerId);
        var set = find(tenantId, setId);
        var questions = store.questions(tenantId, setId).stream().filter(viewer::sees).toList();
        var last = runs.runs(tenantId, setId).stream().findFirst()
                .map(run -> comparisons.compare(viewer, run).summary()).orElse(null);
        return view(viewer, set, questions, states.of(tenantId, questions), last);
    }

    @Transactional
    public SetView createSet(TenantId tenantId, UserId actor, @Nullable String name, @Nullable String description) {
        var set = store.createSet(tenantId, actor, QuestionInputs.name(name), QuestionInputs.description(description),
                now());
        return view(viewers.of(tenantId, actor), set, List.of(), new Existing(Map.of()), null);
    }

    @Transactional
    public SetView updateSet(TenantId tenantId, UserId actor, long setId, @Nullable String name,
            @Nullable String description) {
        find(tenantId, setId);
        store.updateSet(tenantId, setId, QuestionInputs.name(name), QuestionInputs.description(description), now());
        return set(tenantId, actor, setId);
    }

    @Transactional
    public void deleteSet(TenantId tenantId, long setId) {
        find(tenantId, setId);
        store.deleteSet(tenantId, setId);
    }

    @Transactional(readOnly = true)
    public List<QuestionView> questions(TenantId tenantId, UserId viewerId, long setId) {
        var viewer = viewers.of(tenantId, viewerId);
        find(tenantId, setId);
        var questions = store.questions(tenantId, setId).stream().filter(viewer::sees).toList();
        var existing = states.of(tenantId, questions);
        return questions.stream().map(question -> view(viewer, question, existing)).toList();
    }

    @Transactional
    public QuestionView addQuestion(TenantId tenantId, UserId actor, long setId, QuestionInput input) {
        var viewer = viewers.of(tenantId, actor);
        find(tenantId, setId);
        var saved = store.addQuestion(tenantId, setId, draft(viewer, input), now());
        store.touchSet(tenantId, setId, now());
        return view(viewer, saved, states.of(tenantId, List.of(saved)));
    }

    @Transactional
    public QuestionView updateQuestion(TenantId tenantId, UserId actor, long setId, long questionId,
            QuestionInput input) {
        var viewer = viewers.of(tenantId, actor);
        visibleQuestion(viewer, setId, questionId);
        var draft = draft(viewer, input);
        store.updateQuestion(tenantId, questionId, draft);
        store.touchSet(tenantId, setId, now());
        var updated = visibleQuestion(viewer, setId, questionId);
        return view(viewer, updated, states.of(tenantId, List.of(updated)));
    }

    @Transactional
    public void deleteQuestion(TenantId tenantId, UserId actor, long setId, long questionId) {
        visibleQuestion(viewers.of(tenantId, actor), setId, questionId);
        store.deleteQuestion(tenantId, questionId);
        store.touchSet(tenantId, setId, now());
    }

    /** 试问和标准题可选的知识库。 */
    @Transactional(readOnly = true)
    public List<KnowledgeBaseOption> knowledgeBases(TenantId tenantId, UserId viewerId) {
        return viewers.of(tenantId, viewerId).knowledgeBases().entrySet().stream()
                .map(entry -> new KnowledgeBaseOption(entry.getKey(), entry.getValue())).toList();
    }

    /** 选期望文档：能访问的库里已入库的文档，按文件名搜，最多 {@value #DOCUMENT_OPTIONS} 份。 */
    @Transactional(readOnly = true)
    public List<DocumentOption> documents(TenantId tenantId, UserId viewerId, long knowledgeBaseId,
            @Nullable String keyword) {
        return documents.ready(tenantId, viewerId, knowledgeBaseId, keyword, DOCUMENT_OPTIONS).stream()
                .map(document -> new DocumentOption(document.id(), document.filename())).toList();
    }

    EvalSet find(TenantId tenantId, long setId) {
        return store.set(tenantId, setId).orElseThrow(() -> EvaluationException.notFound("题集"));
    }

    private GoldenQuestion visibleQuestion(Viewer viewer, long setId, long questionId) {
        return store.questions(viewer.tenantId(), setId).stream()
                .filter(question -> question.id() == questionId && viewer.sees(question)).findFirst()
                .orElseThrow(() -> EvaluationException.notFound("标准题"));
    }

    /** 校验输入：库要能访问；期望命中文档时至少一份，且都是这个库里已入库的文档。 */
    QuestionDraft draft(Viewer viewer, QuestionInput input) {
        var question = QuestionInputs.question(input.question());
        var expectation = QuestionInputs.expectation(input.expectation());
        var match = QuestionInputs.match(input.match());
        if (!viewer.sees(input.knowledgeBaseId())) {
            throw EvaluationException.notFound("知识库");
        }
        if (expectation == Expectation.REFUSAL) {
            return new QuestionDraft(question, input.knowledgeBaseId(), expectation, match, List.of());
        }
        var ids = input.expectedDocumentIds() == null ? List.<Long>of()
                : input.expectedDocumentIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw EvaluationException.invalid("请至少选一份期望文档，或改为“应当拒答”");
        }
        var found = documents.existing(viewer.tenantId(), ids);
        var expected = ids.stream().map(found::get)
                .filter(document -> document != null && document.knowledgeBaseId() == input.knowledgeBaseId())
                .map(SuiteService::expected).toList();
        if (expected.size() != ids.size()) {
            throw EvaluationException.invalid("期望文档要选这个知识库里现有的文档");
        }
        return new QuestionDraft(question, input.knowledgeBaseId(), expectation, match, expected);
    }

    static ExpectedDocument expected(DocumentRef document) {
        return new ExpectedDocument(document.id(), document.filename());
    }

    private static SetView view(Viewer viewer, EvalSet set, List<GoldenQuestion> questions, Existing existing,
            @Nullable RunSummary lastRun) {
        var names = questions.stream().map(question -> viewer.knowledgeBaseName(question.knowledgeBaseId()))
                .distinct().toList();
        int stale = (int) questions.stream().filter(existing::stale).count();
        return new SetView(set.id(), set.name(), set.description(), questions.size(), stale, names, lastRun,
                set.updatedAt());
    }

    static QuestionView view(Viewer viewer, GoldenQuestion question, Existing existing) {
        var expected = question.expected().stream()
                .map(document -> new QuestionView.ExpectedDocumentView(document.documentId(),
                        existing.filename(document), existing.deleted(document.documentId())))
                .toList();
        return new QuestionView(question.id(), question.question(), question.knowledgeBaseId(),
                viewer.knowledgeBaseName(question.knowledgeBaseId()), question.expectation(), question.match(),
                expected, existing.stale(question), question.createdAt());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
