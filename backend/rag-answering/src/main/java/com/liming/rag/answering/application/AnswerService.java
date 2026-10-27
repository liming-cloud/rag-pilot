package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.ai.RerankClient;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.answering.domain.CitationParser;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import java.util.List;
import java.util.function.Consumer;
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
    private final ChatGateway chat;
    private final int evidenceLimit;
    private final double minRelevance;

    AnswerService(KnowledgeSearch search, RerankClient rerank, ChatGateway chat,
            @Value("${rag.answering.candidates:30}") int candidates,
            @Value("${rag.answering.evidence-limit:8}") int evidenceLimit,
            @Value("${rag.answering.min-relevance:0.2}") double minRelevance) {
        this.retriever = new KnowledgeDocumentRetriever(search, candidates);
        this.reranker = new RerankPostProcessor(rerank);
        this.chat = chat;
        this.evidenceLimit = evidenceLimit;
        this.minRelevance = minRelevance;
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
        var text = question == null ? "" : question.strip();
        if (text.isEmpty() || text.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("问题不能为空，且不超过 " + MAX_QUESTION_LENGTH + " 个字");
        }
        var clock = new StageClock();
        var query = AnswerContext.query(text, tenantId, userId, knowledgeBaseIds);
        var candidates = retriever.retrieve(query);
        clock.retrieved();
        if (candidates.isEmpty()) {
            return Answer.refused(RefusalReason.NO_EVIDENCE, null).withTimings(clock.timings());
        }
        var ranked = reranker.process(query, candidates);
        clock.reranked();
        double topRelevance = ranked.getFirst().getScore();
        var evidence = ranked.stream()
                .filter(document -> document.getScore() >= minRelevance)
                .limit(evidenceLimit)
                .toList();
        if (evidence.isEmpty()) {
            return Answer.refused(RefusalReason.LOW_RELEVANCE, topRelevance).withTimings(clock.timings());
        }
        var prompt = augmenter.augment(query, evidence).text();
        var reply = chat.stream(AnswerPrompt.SYSTEM, prompt, new NoAnswerGate(onDelta, clock::firstToken)).strip();
        clock.generated();
        if (reply.isEmpty() || reply.startsWith(AnswerPrompt.NO_ANSWER)) {
            return Answer.refused(RefusalReason.MODEL_DECLINED, topRelevance).withTimings(clock.timings());
        }
        var parsed = CitationParser.parse(reply, evidence.size());
        if (parsed.cited().isEmpty()) {
            return Answer.refused(RefusalReason.UNCITED, topRelevance).withTimings(clock.timings());
        }
        var citations = parsed.cited().stream()
                .map(index -> Evidence.citation(index, evidence.get(index - 1)))
                .toList();
        return Answer.answered(parsed.text(), citations, topRelevance).withTimings(clock.timings());
    }

    /** 记下各阶段结束的时刻；首字只记第一次。 */
    private static final class StageClock {

        private final long start = System.nanoTime();
        private long retrieved;
        private long reranked;
        private long firstToken;
        private long generated;

        void retrieved() {
            retrieved = System.nanoTime();
        }

        void reranked() {
            reranked = System.nanoTime();
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
            return new Answer.Timings(millis(start, retrieved), reranked == 0 ? null : millis(retrieved, reranked),
                    firstToken == 0 ? null : millis(reranked, firstToken),
                    generated == 0 ? null : millis(reranked, generated));
        }

        private static long millis(long from, long to) {
            return (to - from) / 1_000_000;
        }
    }

    /**
     * 先攒住开头的文字，确认不是 NO_ANSWER 之后再放行，之后的片段直接转发。
     * {@code onModelDelta} 在收到模型的任何片段时调用（包括被拦下的），用来记模型首字时间。
     */
    private static final class NoAnswerGate implements Consumer<String> {

        private final Consumer<String> downstream;
        private final Runnable onModelDelta;
        private final StringBuilder head = new StringBuilder();
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
                downstream.accept(delta);
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
                downstream.accept(head.toString());
            }
        }
    }

}
