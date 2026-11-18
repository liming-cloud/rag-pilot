package com.liming.rag.evaluation.application.trial;

import com.liming.rag.answering.api.AnswerProbe;
import com.liming.rag.answering.api.ProbeAnswer.ProbePassage;
import com.liming.rag.evaluation.application.EvaluationException;
import com.liming.rag.evaluation.application.EvaluationUsage;
import com.liming.rag.evaluation.application.Viewers;
import com.liming.rag.evaluation.application.trial.TrialResult.CitedDocument;
import com.liming.rag.evaluation.application.trial.TrialResult.TrialPassage;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** 试问（#224）：在一个能访问的库里按自己的权限检索和回答，不写回答记录，用量记为“评测”。 */
@Service
public class TrialService {

    private final AnswerProbe probe;
    private final EvaluationUsage usage;
    private final Viewers viewers;

    TrialService(AnswerProbe probe, EvaluationUsage usage, Viewers viewers) {
        this.probe = probe;
        this.usage = usage;
        this.viewers = viewers;
    }

    public TrialResult ask(TenantId tenantId, UserId userId, long knowledgeBaseId, @Nullable String question) {
        if (!viewers.of(tenantId, userId).sees(knowledgeBaseId)) {
            throw EvaluationException.notFound("知识库");
        }
        var answer = usage.attribute(() -> probe.ask(tenantId, userId, knowledgeBaseId, question == null ? "" : question));
        var cited = new LinkedHashMap<Long, CitedDocument>();
        answer.passages().stream().filter(ProbePassage::cited).forEach(passage -> cited.putIfAbsent(
                passage.documentId(), new CitedDocument(passage.documentId(), passage.documentName())));
        var passages = answer.passages().stream().map(passage -> new TrialPassage(passage.documentId(),
                passage.documentName(), passage.headingPath(), passage.excerpt(), passage.relevance(), passage.cited()))
                .toList();
        return new TrialResult(answer.text(), answer.refused(), answer.refusalReason(), List.copyOf(cited.values()),
                passages);
    }
}
