package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Answer.Rewrite.Outcome;
import com.liming.rag.answering.api.Answer;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * 重排后都低于门槛时的补救：把问题改写一次再检索、重排，最高分比原来高才采用（D-022）。口语、错别字、叫法不同常让重排打低分；
 * 正常问题不走这里，延迟不变。改写前最高分低于下限时改写也救不回（评测里全是这样），直接跳过，不让员工白等（D-023）。
 */
final class LowRelevanceRecovery {

    /** 开始改写时发给前端的状态提示。 */
    static final String REWRITING_STATUS = "换个说法再找找…";

    private final DocumentRetriever retriever;
    private final DocumentPostProcessor reranker;
    private final LowRelevanceRewriter rewriter;
    private final double floor;

    LowRelevanceRecovery(DocumentRetriever retriever, DocumentPostProcessor reranker, LowRelevanceRewriter rewriter,
            double floor) {
        this.retriever = retriever;
        this.reranker = reranker;
        this.rewriter = rewriter;
        this.floor = floor;
    }

    /** @param ranked 本次重排结果，非空 */
    Result recover(Query query, List<Document> ranked, StageClock clock, Consumer<String> onStatus) {
        if (ranked.getFirst().getScore() < floor) {
            return new Result(ranked, new Answer.Rewrite(Outcome.SKIPPED_BELOW_FLOOR, null, 0, null, null, null));
        }
        onStatus.accept(REWRITING_STATUS);
        clock.rewriteStarted();
        var rewritten = rewriter.rewrite(query);
        var best = ranked;
        Long retrievalMillis = null;
        Long rerankMillis = null;
        if (rewritten.outcome() == Outcome.REWRITTEN) {
            long t0 = System.nanoTime();
            var retried = retriever.retrieve(rewritten.query());
            long t1 = System.nanoTime();
            var reranked = retried.isEmpty() ? List.<Document>of() : reranker.process(rewritten.query(), retried);
            long t2 = System.nanoTime();
            retrievalMillis = (t1 - t0) / 1_000_000;
            rerankMillis = (t2 - t1) / 1_000_000;
            if (!reranked.isEmpty() && reranked.getFirst().getScore() > ranked.getFirst().getScore()) {
                best = reranked;
            }
        }
        clock.rewriteFinished();
        var question = rewritten.outcome() == Outcome.REWRITTEN ? rewritten.query().text() : null;
        return new Result(best, new Answer.Rewrite(rewritten.outcome(), question, rewritten.outputChars(),
                rewritten.modelMillis(), retrievalMillis, rerankMillis));
    }

    /**
     * @param ranked  采用的重排结果：改写后分数更高时是新结果，否则是原结果
     * @param rewrite 改写的过程记录，写进回答供评测报告统计
     */
    record Result(List<Document> ranked, Answer.Rewrite rewrite) {
    }
}
