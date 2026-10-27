package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.ai.RerankClient;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.answering.api.Citation;
import com.liming.rag.answering.domain.CitationParser;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 带引用的问答：混合检索 → 重排 → 相关度门槛 → 生成 → 引用校验。
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

    private final KnowledgeSearch search;
    private final RerankClient rerank;
    private final ChatGateway chat;
    private final int candidates;
    private final int evidenceLimit;
    private final double minRelevance;

    AnswerService(KnowledgeSearch search, RerankClient rerank, ChatGateway chat,
            @Value("${rag.answering.candidates:30}") int candidates,
            @Value("${rag.answering.evidence-limit:8}") int evidenceLimit,
            @Value("${rag.answering.min-relevance:0.2}") double minRelevance) {
        this.search = search;
        this.rerank = rerank;
        this.chat = chat;
        this.candidates = candidates;
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
        var hits = search.search(new SearchQuery(tenantId, userId, knowledgeBaseIds, text, candidates));
        if (hits.isEmpty()) {
            return Answer.refused(RefusalReason.NO_EVIDENCE, null);
        }
        var ranked = rerank(text, hits);
        double topRelevance = ranked.getFirst().score();
        var evidence = ranked.stream()
                .filter(scored -> scored.score() >= minRelevance)
                .limit(evidenceLimit)
                .map(Scored::hit)
                .toList();
        if (evidence.isEmpty()) {
            return Answer.refused(RefusalReason.LOW_RELEVANCE, topRelevance);
        }
        var reply = chat.stream(AnswerPrompt.SYSTEM, AnswerPrompt.user(text, evidence), new NoAnswerGate(onDelta))
                .strip();
        if (reply.isEmpty() || reply.startsWith(AnswerPrompt.NO_ANSWER)) {
            return Answer.refused(RefusalReason.MODEL_DECLINED, topRelevance);
        }
        var parsed = CitationParser.parse(reply, evidence.size());
        if (parsed.cited().isEmpty()) {
            return Answer.refused(RefusalReason.UNCITED, topRelevance);
        }
        var citations = parsed.cited().stream()
                .map(index -> {
                    var hit = evidence.get(index - 1);
                    return new Citation(index, hit.chunkId(), hit.documentId(), hit.documentName(),
                            hit.headingPath(), hit.content());
                })
                .toList();
        return Answer.answered(parsed.text(), citations, topRelevance);
    }

    private record Scored(SearchHit hit, double score) {
    }

    /** 先攒住开头的文字，确认不是 NO_ANSWER 之后再放行，之后的片段直接转发。 */
    private static final class NoAnswerGate implements Consumer<String> {

        private final Consumer<String> downstream;
        private final StringBuilder head = new StringBuilder();
        private boolean open;
        private boolean declined;

        NoAnswerGate(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void accept(String delta) {
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

    /** 按重排分数从高到低排列全部候选。 */
    private List<Scored> rerank(String question, List<SearchHit> hits) {
        var scores = rerank.score(question, hits.stream()
                .map(hit -> hit.headingPath() + "\n" + hit.content())
                .toList());
        return IntStream.range(0, hits.size())
                .mapToObj(i -> new Scored(hits.get(i), scores.get(i)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
    }
}
