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
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 带引用的问答：混合检索 → 重排 → 相关度门槛 → 生成 → 引用校验。
 *
 * <p>三道拒答关口：没有检索结果、重排后都低于门槛、模型回答 NO_ANSWER 或没有有效引用。
 * 门槛 {@code rag.answering.min-relevance} 是占位值，要用评测集校准。
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
        var reply = chat.complete(AnswerPrompt.SYSTEM, AnswerPrompt.user(text, evidence)).strip();
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
