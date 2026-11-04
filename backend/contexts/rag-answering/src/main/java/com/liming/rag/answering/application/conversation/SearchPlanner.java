package com.liming.rag.answering.application.conversation;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.answering.domain.Question;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * 追问时的检索规划（D-036）：参考对话 agent“模型读完历史、自己写检索词”的做法，但检索由系统执行。
 * 模型读最近几轮和当前问题，给出 0–2 个补全后的独立检索词；判断是寒暄时不检索。模型的回复用 rag-ai 的结构化输出
 * 解析成 {@link Reply}（#145，原来按行解析“检索：”）。超时、出错、格式不对时用原问题检索，不影响回答。
 */
final class SearchPlanner {

    static final String SYSTEM = """
            你负责理解员工在多轮对话里的提问，决定要用什么去检索资料。只做规划，不要回答问题。
            规则：
            1. 需要查资料时，queries 给出 1 到 2 个完整、独立的检索问题：补全当前问题里省略或指代的对象，
               例如前面问过“A 的保修期多久”，当前问“那 B 呢”，就给出“B 的保修期多久”。smallTalk 为 false。
            2. 只有当前问题涉及两个不同的对象、需要分别查时，才给出 2 个。
            3. 当前这句只是寒暄、致谢、问候、表示知道了（如“好的，谢谢”“明白了”“辛苦了”“你好”），
               不需要查任何资料时，smallTalk 为 true、queries 为空；即使前面一直在问业务问题也一样。
            4. 不要添加对话里没有的条件，不要解释。
            """;

    /** 两个检索词加上 JSON 的字段名用不了这么多 token。 */
    static final int MAX_TOKENS = 160;

    static final int MAX_QUERIES = 2;

    private static final TextGeneration.Limits LIMITS = new TextGeneration.Limits(MAX_TOKENS, List.of());

    /**
     * 模型的回复（结构化输出）。
     *
     * @param smallTalk 寒暄，不检索
     * @param queries 补全后的检索问题，0–2 个
     */
    record Reply(boolean smallTalk, List<String> queries) {
    }

    enum Outcome {
        /** 模型给出了检索词，或判断为寒暄。 */
        PLANNED,
        TIMED_OUT,
        /** 模型出错或输出格式不对。 */
        FAILED
    }

    /**
     * @param queries 要检索的问题；寒暄时为空
     */
    record Plan(List<String> queries, boolean smallTalk, Outcome outcome, long modelMillis) {
    }

    private final TextGeneration chat;
    private final Duration timeout;
    /** 每次规划在这里另起一个虚拟线程，超时后不再等它。 */
    private final AsyncTaskExecutor calls;

    SearchPlanner(TextGeneration chat, Duration timeout, AsyncTaskExecutor calls) {
        this.chat = chat;
        this.timeout = timeout;
        this.calls = calls;
    }

    /** 不需要规划时（没有历史）直接用原问题检索。 */
    static Plan direct(String question) {
        return new Plan(List.of(question), false, Outcome.PLANNED, 0);
    }

    Plan plan(String history, String question) {
        long start = System.nanoTime();
        var message = "前面的对话：\n" + history + "\n\n当前问题：" + question;
        var call = calls.submit(() -> chat.structured(SYSTEM, message, Reply.class, LIMITS));
        Reply reply;
        try {
            reply = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            return fallback(question, Outcome.TIMED_OUT, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(question, Outcome.TIMED_OUT, start);
        } catch (ExecutionException e) {
            return fallback(question, Outcome.FAILED, start);
        }
        return parse(reply, question, start);
    }

    /** 寒暄时不检索；否则取可用的检索词，一个也没有时退回原问题。 */
    private static Plan parse(Reply reply, String question, long start) {
        if (reply.smallTalk()) {
            return new Plan(List.of(), true, Outcome.PLANNED, millisSince(start));
        }
        var queries = queries(reply.queries() == null ? List.of() : reply.queries());
        if (queries.isEmpty()) {
            return fallback(question, Outcome.FAILED, start);
        }
        return new Plan(queries, false, Outcome.PLANNED, millisSince(start));
    }

    /** 去掉首尾引号和空白，丢掉空的、太长的和重复的，最多 {@value #MAX_QUERIES} 个。 */
    private static List<String> queries(List<String> raw) {
        var queries = new LinkedHashSet<String>();
        for (var item : raw) {
            if (queries.size() == MAX_QUERIES) {
                break;
            }
            var query = (item == null ? "" : item).strip().replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", "").strip();
            if (Question.fits(query)) {
                queries.add(query);
            }
        }
        return List.copyOf(queries);
    }

    private static Plan fallback(String question, Outcome outcome, long start) {
        return new Plan(List.of(question), false, outcome, millisSince(start));
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
