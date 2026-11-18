package com.liming.rag.evaluation.application.suite;

import com.liming.rag.evaluation.application.EvaluationException;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.QuestionDraft;
import com.liming.rag.evaluation.application.SuiteStore;
import com.liming.rag.evaluation.application.Viewers;
import com.liming.rag.evaluation.application.Viewers.Viewer;
import com.liming.rag.evaluation.application.suite.ImportOutcome.Skipped;
import com.liming.rag.knowledge.api.DocumentRef;
import com.liming.rag.knowledge.api.KnowledgeDocuments;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 CSV 导入标准题（#224）：知识库、期望文档按名称匹配，只在操作人能访问的库里找；匹配不上或不合规的行跳过并说明原因，
 * 其余照常导入。
 */
@Service
public class QuestionImport {

    static final int MAX_ROWS = 500;

    private final SuiteService suites;
    private final SuiteStore store;
    private final Viewers viewers;
    private final KnowledgeDocuments documents;
    private final Clock clock;

    QuestionImport(SuiteService suites, SuiteStore store, Viewers viewers, KnowledgeDocuments documents, Clock clock) {
        this.suites = suites;
        this.store = store;
        this.viewers = viewers;
        this.documents = documents;
        this.clock = clock;
    }

    @Transactional
    public ImportOutcome importRows(TenantId tenantId, UserId actor, long setId, List<ImportRow> rows) {
        suites.find(tenantId, setId);
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw EvaluationException.invalid("一次导入 1 到 " + MAX_ROWS + " 道题");
        }
        var viewer = viewers.of(tenantId, actor);
        var byName = new HashMap<String, Long>();
        viewer.knowledgeBases().forEach((id, name) -> byName.putIfAbsent(name, id));
        var skipped = new ArrayList<Skipped>();
        int imported = 0;
        var now = OffsetDateTime.now(clock);
        for (var row : rows) {
            try {
                store.addQuestion(tenantId, setId, draft(viewer, byName, row), now);
                imported++;
            } catch (EvaluationException e) {
                skipped.add(new Skipped(row.line(), e.getMessage()));
            }
        }
        if (imported > 0) {
            store.touchSet(tenantId, setId, now);
        }
        return new ImportOutcome(imported, List.copyOf(skipped));
    }

    private QuestionDraft draft(Viewer viewer, Map<String, Long> byName, ImportRow row) {
        var knowledgeBaseId = Optional.ofNullable(byName.get(row.knowledgeBase().strip()))
                .orElseThrow(() -> EvaluationException.invalid("知识库“" + row.knowledgeBase() + "”不存在或无权访问"));
        var names = row.documents().stream().map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
        var found = documents.named(viewer.tenantId(), viewer.userId(), knowledgeBaseId, names);
        var ids = new ArrayList<Long>();
        for (var name : names) {
            ids.add(found.stream().filter(document -> document.filename().equals(name)).findFirst()
                    .map(DocumentRef::id)
                    .orElseThrow(() -> EvaluationException.invalid("期望文档“" + name + "”在这个库里找不到")));
        }
        var expectation = names.isEmpty() ? Expectation.REFUSAL : Expectation.DOCUMENTS;
        return suites.draft(viewer, new QuestionInput(row.question(), knowledgeBaseId, expectation.name(),
                row.match(), ids));
    }
}
