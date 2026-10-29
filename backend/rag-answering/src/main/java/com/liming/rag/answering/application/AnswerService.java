package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.ai.RerankClient;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.answering.domain.CitationParser;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 带引用的问答：混合检索 → 重排 → 相关度门槛 → 生成 → 引用校验。
 *
 * <p>检索、重排、拼证据用 Spring AI 模块化 RAG 的接口（{@link DocumentRetriever}、{@link DocumentPostProcessor}、
 * {@link QueryAugmenter}），编排和拒答规则在这里，见 D-020。
 *
 * <p>三道拒答关口：没有检索结果、重排后都低于门槛、模型回答 NO_ANSWER 或没有有效引用。
 * 重排后都低于门槛时，先把问题改写一次再检索（{@link LowRelevanceRewriter}，可用 {@code rag.answering.rewrite-on-low-relevance} 关闭）。
 * 门槛 {@code rag.answering.min-relevance} 是占位值，要用评测集校准。
 *
 * <p>流式问答时，生成中的文字先流给用户，最终以返回的 {@link Answer} 为准：引用校验不通过时它是拒答，
 * 调用方要用它替换已流出的文字。模型回复 NO_ANSWER 时一个字也不流出。
 */
@Service
public class AnswerService {

    static final int MAX_QUESTION_LENGTH = 500;

    private final DocumentRetriever retriever;
    private final DocumentPostProcessor reranker;
    private final QueryAugmenter augmenter = new NumberedEvidenceAugmenter();
    /** 开始改写时发给前端的状态提示。 */
    static final String REWRITING_STATUS = "换个说法再找找…";

    private final LowRelevanceRewriter rewriter;
    private final ChatGateway chat;
    private final int evidenceLimit;
    private final double minRelevance;
    private final boolean rewriteOnLowRelevance;
    private final double rewriteMinRelevance;

    AnswerService(KnowledgeSearch search, RerankClient rerank, ChatGateway chat,
            @Value("${rag.answering.candidates:30}") int candidates,
            @Value("${rag.answering.evidence-limit:8}") int evidenceLimit,
            @Value("${rag.answering.min-relevance:0.2}") double minRelevance,
            @Value("${rag.answering.rewrite-on-low-relevance:true}") boolean rewriteOnLowRelevance,
            @Value("${rag.answering.rewrite-min-relevance:0.03}") double rewriteMinRelevance,
            @Value("${rag.answering.rewrite-timeout:3s}") Duration rewriteTimeout) {
        this.retriever = new KnowledgeDocumentRetriever(search, candidates);
        this.reranker = new RerankPostProcessor(rerank);
        this.rewriter = new LowRelevanceRewriter(chat, rewriteTimeout);
        this.chat = chat;
        this.evidenceLimit = evidenceLimit;
        this.minRelevance = minRelevance;
        this.rewriteOnLowRelevance = rewriteOnLowRelevance;
        this.rewriteMinRelevance = rewriteMinRelevance;
    }

    /**
     * @param knowledgeBaseIds 想缩小到的知识库，可以为空；只会在提问人是成员的知识库里检索
     */
    public Answer ask(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question) {
        return ask(tenantId, userId, knowledgeBaseIds, question, delta -> {
        });
    }

    /**
     * 流式问答：生成中的文字交给 {@code onDelta}，返回值是最终结果。检索阶段就拒答时不会调用 {@code onDelta}。
     */
    public Answer ask(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question,
            Consumer<String> onDelta) {
        return ask(tenantId, userId, knowledgeBaseIds, question, onDelta, status -> {
        });
    }

    /**
     * 流式问答，另外把处理中的状态（如“换个说法再找找…”）交给 {@code onStatus}，前端据此提示员工系统还在找。
     */
    public Answer ask(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question,
            Consumer<String> onDelta, Consumer<String> onStatus) {
        var text = question == null ? "" : question.strip();
        if (text.isEmpty() || text.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("问题不能为空，且不超过 " + MAX_QUESTION_LENGTH + " 个字");
        }
        var clock = new StageClock();
        var query = AnswerContext.query(text, tenantId, userId, knowledgeBaseIds);
        var candidates = retriever.retrieve(query);
        clock.retrieved();
        if (candidates.isEmpty()) {
            return finish(Answer.refused(RefusalReason.NO_EVIDENCE, null), clock, null);
        }
        var ranked = reranker.process(query, candidates);
        clock.reranked();
        var evidence = evidence(ranked);
        Answer.Rewrite rewrite = null;
        if (evidence.isEmpty() && rewriteOnLowRelevance) {
            // 低分时改写一次问题再检索：口语、错别字、叫法不同常让重排打低分。正常问题不走这里，延迟不变。
            // 最高分低于下限的问题改写也救不回（评测里全是这样），直接拒答，不让员工白等。
            if (ranked.getFirst().getScore() < rewriteMinRelevance) {
                rewrite = new Answer.Rewrite(Answer.Rewrite.Outcome.SKIPPED_BELOW_FLOOR, null, 0, null, null, null);
            } else {
                onStatus.accept(REWRITING_STATUS);
                clock.rewriteStarted();
                var result = rewriter.rewrite(query);
                Long retrievalMillis = null;
                Long rerankMillis = null;
                if (result.outcome() == Answer.Rewrite.Outcome.REWRITTEN) {
                    long t0 = System.nanoTime();
                    var retried = retriever.retrieve(result.query());
                    long t1 = System.nanoTime();
                    var reranked = retried.isEmpty() ? List.<Document>of() : reranker.process(result.query(), retried);
                    long t2 = System.nanoTime();
                    retrievalMillis = (t1 - t0) / 1_000_000;
                    rerankMillis = (t2 - t1) / 1_000_000;
                    if (!reranked.isEmpty() && reranked.getFirst().getScore() > ranked.getFirst().getScore()) {
                        ranked = reranked;
                        evidence = evidence(ranked);
                    }
                }
                clock.rewriteFinished();
                rewrite = new Answer.Rewrite(result.outcome(),
                        result.outcome() == Answer.Rewrite.Outcome.REWRITTEN ? result.query().text() : null,
                        result.outputChars(), result.modelMillis(), retrievalMillis, rerankMillis);
            }
        }
        double topRelevance = ranked.getFirst().getScore();
        if (evidence.isEmpty()) {
            return finish(Answer.refused(RefusalReason.LOW_RELEVANCE, topRelevance), clock, rewrite);
        }
        // 证据可能来自改写后的检索，但回答针对员工原来的问题。
        var prompt = augmenter.augment(query, evidence).text();
        var gate = new NoAnswerGate(onDelta, clock::firstToken);
        var raw = chat.stream(AnswerPrompt.SYSTEM, prompt, gate).strip();
        gate.flush();
        clock.generated();
        // 模型有时先答一部分，再在中间或末尾补一个 NO_ANSWER：去掉标记，剩下的照常校验引用。
        var reply = raw.replace(AnswerPrompt.NO_ANSWER, "").strip();
        if (reply.isEmpty() || raw.startsWith(AnswerPrompt.NO_ANSWER)) {
            return finish(Answer.refused(RefusalReason.MODEL_DECLINED, topRelevance), clock, rewrite);
        }
        var parsed = CitationParser.parse(reply, evidence.size());
        if (parsed.cited().isEmpty()) {
            return finish(Answer.refused(RefusalReason.UNCITED, topRelevance), clock, rewrite);
        }
        var finalEvidence = evidence;
        var citations = parsed.cited().stream()
                .map(index -> Evidence.citation(index, finalEvidence.get(index - 1)))
                .toList();
        return finish(Answer.answered(parsed.text(), citations, topRelevance), clock, rewrite);
    }

    /** 重排后达到门槛的前几条。 */
    private List<Document> evidence(List<Document> ranked) {
        return ranked.stream()
                .filter(document -> document.getScore() >= minRelevance)
                .limit(evidenceLimit)
                .toList();
    }

    private static Answer finish(Answer answer, StageClock clock, Answer.Rewrite rewrite) {
        return answer.withRewrite(rewrite).withTimings(clock.timings());
    }

    /** 记下各阶段结束的时刻；首字只记第一次。 */
    private static final class StageClock {

        private final long start = System.nanoTime();
        private long retrieved;
        private long reranked;
        private long firstToken;
        private long generated;
        private long rewriteStarted;
        private long rewriteFinished;

        void retrieved() {
            retrieved = System.nanoTime();
        }

        void reranked() {
            reranked = System.nanoTime();
        }

        void rewriteStarted() {
            rewriteStarted = System.nanoTime();
        }

        void rewriteFinished() {
            rewriteFinished = System.nanoTime();
        }

        void firstToken() {
            if (firstToken == 0) {
                firstToken = System.nanoTime();
            }
        }

        void generated() {
            generated = System.nanoTime();
            firstToken();
        }

        Answer.Timings timings() {
            // 生成从重排结束（有改写时从改写结束）开始算。
            long generationStart = rewriteFinished == 0 ? reranked : rewriteFinished;
            return new Answer.Timings(millis(start, retrieved), reranked == 0 ? null : millis(retrieved, reranked),
                    firstToken == 0 ? null : millis(generationStart, firstToken),
                    generated == 0 ? null : millis(generationStart, generated),
                    rewriteFinished == 0 ? null : millis(rewriteStarted, rewriteFinished));
        }

        private static long millis(long from, long to) {
            return (to - from) / 1_000_000;
        }
    }

    /**
     * 先攒住开头的文字，确认不是 NO_ANSWER 之后再放行；放行之后也过滤掉夹在中间或末尾的 NO_ANSWER，
     * 末尾可能是标记开头的几个字先留着，等下一段或 {@link #flush()} 再决定。
     * {@code onModelDelta} 在收到模型的任何片段时调用（包括被拦下的），用来记模型首字时间。
     */
    private static final class NoAnswerGate implements Consumer<String> {

        private final Consumer<String> downstream;
        private final Runnable onModelDelta;
        private final StringBuilder head = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private boolean open;
        private boolean declined;

        NoAnswerGate(Consumer<String> downstream, Runnable onModelDelta) {
            this.downstream = downstream;
            this.onModelDelta = onModelDelta;
        }

        @Override
        public void accept(String delta) {
            onModelDelta.run();
            if (open) {
                forward(delta);
                return;
            }
            if (declined) {
                return;
            }
            head.append(delta);
            var start = head.toString().stripLeading();
            if (start.startsWith(AnswerPrompt.NO_ANSWER)) {
                declined = true;
            } else if (!AnswerPrompt.NO_ANSWER.startsWith(start)) {
                open = true;
                forward(head.toString());
            }
        }

        /** 生成结束时把留着的几个字放出去（它们最终没有组成标记）。 */
        void flush() {
            if (open && !pending.isEmpty()) {
                downstream.accept(pending.toString());
                pending.setLength(0);
            }
        }

        private void forward(String delta) {
            pending.append(delta);
            var text = pending.toString().replace(AnswerPrompt.NO_ANSWER, "");
            int keep = markerPrefixAtEnd(text);
            var ready = text.substring(0, text.length() - keep);
            pending.setLength(0);
            pending.append(text, text.length() - keep, text.length());
            if (!ready.isEmpty()) {
                downstream.accept(ready);
            }
        }

        /** 文字末尾有几个字可能是标记的开头。 */
        private static int markerPrefixAtEnd(String text) {
            for (int length = Math.min(text.length(), AnswerPrompt.NO_ANSWER.length() - 1); length > 0; length--) {
                if (AnswerPrompt.NO_ANSWER.startsWith(text.substring(text.length() - length))) {
                    return length;
                }
            }
            return 0;
        }
    }

}
