package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.ai.RerankClient;
import com.liming.rag.answering.domain.CitationParser;
import com.liming.rag.answering.domain.Question;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.settings.api.AnsweringSettings;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
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
 * <p>流式问答时，生成中的文字先流给用户，最终以返回的 {@link AskResult} 为准：引用校验不通过时它是拒答，
 * 调用方要用它替换已流出的文字。模型回复 NO_ANSWER 时一个字也不流出。
 *
 * <p>提示词开头的角色、引用示例和拒答话术来自行业包和系统设置（D-045）；检索前用行业词表扩展检索用的问题
 * （{@link QueryExpansion}），低分改写和生成仍针对没扩展的问题。
 *
 * <p>会话里追问时（D-036），检索规划给出的 1–2 个检索词各检索一次、合并候选，每条资料取对各检索词重排分数的最高值；
 * 生成时把前几轮对话放在资料之后，回答的依据仍只能是这一轮的资料。首字前按阶段推状态，可以中途停止。
 */
@Service
public class AnswerService {

    static final String SEARCHING_STATUS = "正在检索资料…";
    static final String GENERATING_STATUS = "正在整理答案…";

    /**
     * 一次提问。
     *
     * @param knowledgeBaseIds 想缩小到的知识库，可以为空；只会在提问人是成员的知识库里检索
     * @param question 员工原来的问题，生成时用它
     * @param searchQueries 用来检索的问题（检索规划的结果，1–2 个）；为空时用 question
     * @param history 前几轮对话，没有时为空串
     */
    public record AskRequest(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question,
            List<String> searchQueries, String history) {
    }

    private final DocumentRetriever retriever;
    private final DocumentPostProcessor reranker;
    private final QueryAugmenter augmenter = new NumberedEvidenceAugmenter();
    private final LowRelevanceRecovery recovery;
    private final ChatGateway chat;
    private final AnsweringSettings settings;
    private final String system;
    private final QueryExpansion expansion;
    private final int evidenceLimit;
    private final double minRelevance;
    private final boolean rewriteOnLowRelevance;

    /**
     * @param executor Spring 管理的虚拟线程执行器（{@code applicationTaskExecutor}），停机时等正在跑的任务做完（#135）
     */
    AnswerService(KnowledgeSearch search, RerankClient rerank, ChatGateway chat, AnsweringProperties properties,
            AnsweringSettings settings, @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        var pack = settings.pack();
        this.retriever = new KnowledgeDocumentRetriever(search, properties.candidates());
        this.reranker = new RerankPostProcessor(rerank);
        this.recovery = new LowRelevanceRecovery(retriever, reranker,
                new LowRelevanceRewriter(chat, properties.rewriteTimeout(), pack, executor),
                properties.rewriteMinRelevance());
        this.chat = chat;
        this.settings = settings;
        this.system = AnswerPrompt.system(pack);
        this.expansion = new QueryExpansion(pack.vocabulary());
        this.evidenceLimit = properties.evidenceLimit();
        this.minRelevance = properties.minRelevance();
        this.rewriteOnLowRelevance = properties.rewriteOnLowRelevance();
    }

    /**
     * @param knowledgeBaseIds 想缩小到的知识库，可以为空；只会在提问人是成员的知识库里检索
     */
    public AskResult ask(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question) {
        return ask(tenantId, userId, knowledgeBaseIds, question, delta -> {
        });
    }

    /**
     * 流式问答：生成中的文字交给 {@code onDelta}，返回值是最终结果。检索阶段就拒答时不会调用 {@code onDelta}。
     */
    public AskResult ask(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question,
            Consumer<String> onDelta) {
        return ask(tenantId, userId, knowledgeBaseIds, question, onDelta, status -> {
        });
    }

    /**
     * 流式问答，另外把处理中的状态（如“换个说法再找找…”）交给 {@code onStatus}，前端据此提示员工系统还在找。
     * 这个入口不推“正在检索资料…”“正在整理答案…”两个阶段状态：评测按 /api/ask/stream 的第一个事件计首字，
     * 保持口径不变；阶段状态只在会话提问里推（D-036）。
     */
    public AskResult ask(TenantId tenantId, UserId userId, List<Long> knowledgeBaseIds, String question,
            Consumer<String> onDelta, Consumer<String> onStatus) {
        Consumer<String> withoutStages = status -> {
            if (!SEARCHING_STATUS.equals(status) && !GENERATING_STATUS.equals(status)) {
                onStatus.accept(status);
            }
        };
        return ask(new AskRequest(tenantId, userId, knowledgeBaseIds, question, List.of(), ""), onDelta, withoutStages,
                new CompletableFuture<Void>());
    }

    /**
     * 按检索规划提问（会话用）。{@code stop} 完成后不再生成：生成前就停止时不调用模型，返回的结果由调用方丢弃；
     * 生成中停止时返回已生成部分的校验结果。
     */
    public AskResult ask(AskRequest request, Consumer<String> onDelta, Consumer<String> onStatus, CompletionStage<?> stop) {
        var text = question(request.question());
        var clock = new StageClock();
        onStatus.accept(SEARCHING_STATUS);
        var searchTexts = request.searchQueries() == null || request.searchQueries().isEmpty() ? List.of(text)
                : request.searchQueries();
        var queries = searchTexts.stream()
                .map(searchText -> AnswerContext.query(expansion.expand(searchText), request.tenantId(),
                        request.userId(), request.knowledgeBaseIds()))
                .toList();
        var candidates = union(queries.stream().map(retriever::retrieve).toList());
        clock.retrieved();
        if (candidates.isEmpty()) {
            return finish(refusal(RefusalReason.NO_EVIDENCE, null), clock, null);
        }
        // 低分改写针对没扩展的问题：改写本身会补类别，扩展词混在问题里反而干扰改写。
        var query = AnswerContext.query(searchTexts.getFirst(), request.tenantId(), request.userId(),
                request.knowledgeBaseIds());
        var ranked = rerank(queries, candidates);
        clock.reranked();
        AskResult.Rewrite rewrite = null;
        if (evidence(ranked).isEmpty() && rewriteOnLowRelevance) {
            var recovered = recovery.recover(query, ranked, clock, onStatus);
            ranked = recovered.ranked();
            rewrite = recovered.rewrite();
        }
        double topRelevance = ranked.getFirst().getScore();
        var evidence = evidence(ranked);
        if (evidence.isEmpty()) {
            return finish(refusal(RefusalReason.LOW_RELEVANCE, topRelevance), clock, rewrite);
        }
        if (stop.toCompletableFuture().isDone()) {
            return finish(refusal(RefusalReason.MODEL_DECLINED, topRelevance), clock, rewrite);
        }
        onStatus.accept(GENERATING_STATUS);
        // 证据可能来自规划或改写后的检索，但回答针对员工原来的问题。
        var asked = AnswerContext.query(text, request.tenantId(), request.userId(), request.knowledgeBaseIds(),
                request.history());
        return finish(generate(asked, evidence, topRelevance, clock, onDelta, stop), clock, rewrite);
    }

    /** 员工的问题，规则见 {@link Question}。 */
    static String question(String text) {
        try {
            return new Question(text).text();
        } catch (IllegalArgumentException e) {
            throw new AnsweringException(AnsweringException.Reason.INVALID, e.getMessage());
        }
    }

    /** 合并几个检索词的候选，同一分块只留第一次出现的。 */
    private static List<Document> union(List<List<Document>> results) {
        var seen = new LinkedHashMap<Object, Document>();
        results.forEach(documents -> documents.forEach(
                document -> seen.putIfAbsent(document.getMetadata().get(Evidence.CHUNK_ID), document)));
        return List.copyOf(seen.values());
    }

    /** 每条资料对每个检索词各重排一次，取最高分；同分时保持候选顺序。 */
    private List<Document> rerank(List<Query> queries, List<Document> candidates) {
        if (queries.size() == 1) {
            return reranker.process(queries.getFirst(), candidates);
        }
        var best = new LinkedHashMap<Object, Document>();
        candidates.forEach(document -> best.put(document.getMetadata().get(Evidence.CHUNK_ID), null));
        for (var query : queries) {
            for (var scored : reranker.process(query, candidates)) {
                best.merge(scored.getMetadata().get(Evidence.CHUNK_ID), scored,
                        (current, next) -> current == null || next.getScore() > current.getScore() ? next : current);
            }
        }
        var merged = new ArrayList<Document>(best.values());
        merged.sort(Comparator.comparingDouble(Document::getScore).reversed());
        return merged;
    }

    /** 生成并校验引用：模型拒答、去掉标记后为空或没有有效引用都按拒答处理。 */
    private AskResult generate(Query query, List<Document> evidence, double topRelevance, StageClock clock,
            Consumer<String> onDelta, CompletionStage<?> stop) {
        var prompt = augmenter.augment(query, evidence).text();
        var gate = new NoAnswerGate(onDelta, clock::firstToken);
        var raw = chat.stream(system, prompt, gate, stop).strip();
        gate.flush();
        clock.generated();
        // 模型有时先答一部分，再在中间或末尾补一个 NO_ANSWER：去掉标记，剩下的照常校验引用。
        var reply = raw.replace(AnswerPrompt.NO_ANSWER, "").strip();
        if (reply.isEmpty() || raw.startsWith(AnswerPrompt.NO_ANSWER)) {
            return refusal(RefusalReason.MODEL_DECLINED, topRelevance);
        }
        var parsed = CitationParser.parse(reply, evidence.size());
        if (parsed.cited().isEmpty()) {
            return refusal(RefusalReason.UNCITED, topRelevance);
        }
        var citations = parsed.cited().stream()
                .map(index -> Evidence.citation(index, evidence.get(index - 1)))
                .toList();
        return AskResult.answered(parsed.text(), citations, topRelevance);
    }

    /** 拒答，话术用系统设置里当前的（D-045）。 */
    public AskResult refusal(RefusalReason reason, Double topRelevance) {
        return AskResult.refused(reason, topRelevance, settings.refusalMessage());
    }

    /** 重排后达到门槛的前几条。 */
    private List<Document> evidence(List<Document> ranked) {
        return ranked.stream()
                .filter(document -> document.getScore() >= minRelevance)
                .limit(evidenceLimit)
                .toList();
    }

    private static AskResult finish(AskResult answer, StageClock clock, AskResult.Rewrite rewrite) {
        return answer.withRewrite(rewrite).withTimings(clock.timings());
    }
}
