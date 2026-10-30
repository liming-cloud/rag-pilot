package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.ai.RerankClient;
import com.liming.rag.answering.api.Answer.RefusalReason;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.domain.CitationParser;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Service;

/**
 * 带引用的问答：混合检索 → 重排 → 相关度门槛 → 生成 → 引用校验。
 *
 * <p>检索、重排、拼证据用 Spring AI 模块化 RAG 的接口（{@link DocumentRetriever}、{@link DocumentPostProcessor}、
 * {@link QueryAugmenter}），编排和拒答规则在这里，见 D-020。
 *
 * <p>三道拒答关口：没有检索结果、重排后都低于门槛、模型回答 NO_ANSWER 或没有有效引用。
 * 重排后都低于门槛时，先把问题改写一次再检索（{@link LowRelevanceRecovery}，可用 {@code rag.answering.rewrite-on-low-relevance} 关闭）。
 * 配置见 {@link AnsweringProperties}。
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
    private final LowRelevanceRecovery recovery;
    private final ChatGateway chat;
    private final int evidenceLimit;
    private final double minRelevance;
    private final boolean rewriteOnLowRelevance;

    AnswerService(KnowledgeSearch search, RerankClient rerank, ChatGateway chat, AnsweringProperties properties) {
        this.retriever = new KnowledgeDocumentRetriever(search, properties.candidates());
        this.reranker = new RerankPostProcessor(rerank);
        this.recovery = new LowRelevanceRecovery(retriever, reranker,
                new LowRelevanceRewriter(chat, properties.rewriteTimeout()), properties.rewriteMinRelevance());
        this.chat = chat;
        this.evidenceLimit = properties.evidenceLimit();
        this.minRelevance = properties.minRelevance();
        this.rewriteOnLowRelevance = properties.rewriteOnLowRelevance();
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
            throw new AnsweringException(AnsweringException.Reason.INVALID,
                    "问题不能为空，且不超过 " + MAX_QUESTION_LENGTH + " 个字");
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
        Answer.Rewrite rewrite = null;
        if (evidence(ranked).isEmpty() && rewriteOnLowRelevance) {
            var recovered = recovery.recover(query, ranked, clock, onStatus);
            ranked = recovered.ranked();
            rewrite = recovered.rewrite();
        }
        double topRelevance = ranked.getFirst().getScore();
        var evidence = evidence(ranked);
        if (evidence.isEmpty()) {
            return finish(Answer.refused(RefusalReason.LOW_RELEVANCE, topRelevance), clock, rewrite);
        }
        // 证据可能来自改写后的检索，但回答针对员工原来的问题。
        return finish(generate(query, evidence, topRelevance, clock, onDelta), clock, rewrite);
    }

    /** 生成并校验引用：模型拒答、去掉标记后为空或没有有效引用都按拒答处理。 */
    private Answer generate(Query query, List<Document> evidence, double topRelevance, StageClock clock,
            Consumer<String> onDelta) {
        var prompt = augmenter.augment(query, evidence).text();
        var gate = new NoAnswerGate(onDelta, clock::firstToken);
        var raw = chat.stream(AnswerPrompt.SYSTEM, prompt, gate).strip();
        gate.flush();
        clock.generated();
        // 模型有时先答一部分，再在中间或末尾补一个 NO_ANSWER：去掉标记，剩下的照常校验引用。
        var reply = raw.replace(AnswerPrompt.NO_ANSWER, "").strip();
        if (reply.isEmpty() || raw.startsWith(AnswerPrompt.NO_ANSWER)) {
            return Answer.refused(RefusalReason.MODEL_DECLINED, topRelevance);
        }
        var parsed = CitationParser.parse(reply, evidence.size());
        if (parsed.cited().isEmpty()) {
            return Answer.refused(RefusalReason.UNCITED, topRelevance);
        }
        var citations = parsed.cited().stream()
                .map(index -> Evidence.citation(index, evidence.get(index - 1)))
                .toList();
        return Answer.answered(parsed.text(), citations, topRelevance);
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
}
