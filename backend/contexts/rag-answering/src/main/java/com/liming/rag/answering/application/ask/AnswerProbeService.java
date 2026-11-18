package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.api.AnswerProbe;
import com.liming.rag.answering.api.ProbeAnswer;
import com.liming.rag.answering.api.ProbeAnswer.ProbePassage;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@link AnswerProbe} 的实现：直接用 {@link AnswerService}，顺带记下重排后的前几条资料（#224）。 */
@Service
class AnswerProbeService implements AnswerProbe {

    private final AnswerService answers;

    AnswerProbeService(AnswerService answers) {
        this.answers = answers;
    }

    @Override
    public ProbeAnswer ask(TenantId tenantId, UserId userId, long knowledgeBaseId, String question) {
        var ranked = new ArrayList<Evidence>();
        var request = new AnswerService.AskRequest(tenantId, userId, List.of(knowledgeBaseId), question, List.of(), "");
        var result = answers.ask(request, delta -> {
        }, status -> {
        }, new CompletableFuture<Void>(), ranked::addAll);
        var cited = result.citations().stream().map(Citation::chunkId).collect(Collectors.toSet());
        var passages = ranked.stream().map(evidence -> new ProbePassage(evidence.documentId(), evidence.documentName(),
                evidence.headingPath(), excerpt(evidence.text()), evidence.score(),
                cited.contains(evidence.chunkId()))).toList();
        var reason = result.refusalReason() == null ? null : result.refusalReason().name();
        return new ProbeAnswer(result.text(), result.refused(), reason, passages);
    }

    private static String excerpt(String text) {
        var stripped = text.strip();
        return stripped.length() <= ProbePassage.EXCERPT_CHARS ? stripped
                : stripped.substring(0, ProbePassage.EXCERPT_CHARS);
    }
}
