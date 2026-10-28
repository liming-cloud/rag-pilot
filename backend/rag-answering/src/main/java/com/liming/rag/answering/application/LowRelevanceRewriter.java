package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.answering.api.Answer.Rewrite.Outcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * 把员工的问题改写成规范说法，供“低分时再检索一次”使用。实现 Spring AI 的 {@link QueryTransformer}，
 * 但模型调用走 rag-ai 的 {@link ChatGateway}，不在这里直接建 ChatClient。
 *
 * <p>提示词只写通用规则，不针对任何评测题。改写只要一句话：限制输出长度并遇换行停止，不等模型生成多余内容；
 * 超过时限就放弃。模型没给出有效改写（空、NO_ANSWER、太长、和原问题一样）时返回原查询。
 */
final class LowRelevanceRewriter implements QueryTransformer {

    static final String SYSTEM = """
            你负责把连锁门店员工的提问改写成便于检索门店制度文件的规范问题。
            规则：
            1. 纠正错别字和同音字。
            2. 把口语、方言、简称改成书面说法。
            3. 提到具体商品时，在后面补上它所属的商品类别。
            4. 不要回答问题，不要添加原问题里没有的条件。
            5. 只输出改写后的一句问题，不要解释。
            """;

    /** 一句问题用不了这么多 token；遇换行就停，模型多写的解释不会被生成。 */
    static final ChatGateway.Limits LIMITS = new ChatGateway.Limits(64, List.of("\n"));

    /** 每次改写一个虚拟线程，超时后不再等它。 */
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    /** 一次改写的结果。 */
    record Result(Query query, Outcome outcome, int outputChars, long modelMillis) {
    }

    private final ChatGateway chat;
    private final Duration timeout;

    LowRelevanceRewriter(ChatGateway chat, Duration timeout) {
        this.chat = chat;
        this.timeout = timeout;
    }

    @Override
    public Query transform(Query query) {
        return rewrite(query).query();
    }

    Result rewrite(Query query) {
        long start = System.nanoTime();
        var call = CALLS.submit(() -> chat.complete(SYSTEM, query.text(), LIMITS));
        String reply;
        try {
            reply = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            return new Result(query, Outcome.TIMED_OUT, 0, millisSince(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(query, Outcome.TIMED_OUT, 0, millisSince(start));
        } catch (ExecutionException e) {
            // 改写失败不影响拒答：模型不可用时按没改写处理。
            return new Result(query, Outcome.NO_CHANGE, 0, millisSince(start));
        }
        long modelMillis = millisSince(start);
        var raw = reply == null ? "" : reply;
        var rewritten = raw.strip().lines().findFirst().orElse("").strip()
                .replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", "");
        if (rewritten.isEmpty() || rewritten.startsWith(AnswerPrompt.NO_ANSWER)
                || rewritten.length() > AnswerService.MAX_QUESTION_LENGTH || rewritten.equals(query.text())) {
            return new Result(query, Outcome.NO_CHANGE, raw.length(), modelMillis);
        }
        return new Result(query.mutate().text(rewritten).build(), Outcome.REWRITTEN, raw.length(), modelMillis);
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
