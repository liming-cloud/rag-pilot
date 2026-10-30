package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 追问时的检索规划（D-036）：参考对话 agent“模型读完历史、自己写检索词”的做法，但检索由系统执行。
 * 模型读最近几轮和当前问题，给出 0–2 个补全后的独立检索词；判断是寒暄时不检索。
 * 超时、出错、格式不对时用原问题检索，不影响回答。
 */
final class SearchPlanner {

    static final String SYSTEM = """
            你负责理解员工在多轮对话里的提问，决定要用什么去检索资料。只做规划，不要回答问题。
            规则：
            1. 需要查资料时，输出 1 到 2 行，每行以“检索：”开头，后面是一个完整、独立的检索问题：
               补全当前问题里省略或指代的对象，例如前面问过“A 的保修期多久”，当前问“那 B 呢”，
               就输出“检索：B 的保修期多久”。
            2. 只有当前问题涉及两个不同的对象、需要分别查时，才输出 2 行。
            3. 当前这句只是寒暄、致谢、问候，不需要查任何资料时，只输出“不检索”。
            4. 不要添加对话里没有的条件，不要解释。
            """;

    /** 两行检索词用不了这么多 token。 */
    static final int MAX_TOKENS = 96;

    static final int MAX_QUERIES = 2;

    private static final ChatGateway.Limits LIMITS = new ChatGateway.Limits(MAX_TOKENS, List.of());
    private static final String NO_SEARCH = "不检索";

    /** 每次规划一个虚拟线程，超时后不再等它。 */
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

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

    private final ChatGateway chat;
    private final Duration timeout;

    SearchPlanner(ChatGateway chat, Duration timeout) {
        this.chat = chat;
        this.timeout = timeout;
    }

    /** 不需要规划时（没有历史）直接用原问题检索。 */
    static Plan direct(String question) {
        return new Plan(List.of(question), false, Outcome.PLANNED, 0);
    }

    Plan plan(String history, String question) {
        long start = System.nanoTime();
        var message = "前面的对话：\n" + history + "\n\n当前问题：" + question;
        var call = CALLS.submit(() -> chat.complete(SYSTEM, message, LIMITS));
        String reply;
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
        var lines = (reply == null ? "" : reply).lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() == 1 && lines.getFirst().replaceAll("[。.！!]", "").equals(NO_SEARCH)) {
            return new Plan(List.of(), true, Outcome.PLANNED, millisSince(start));
        }
        var queries = new LinkedHashSet<String>();
        for (var line : lines) {
            if (queries.size() == MAX_QUERIES) {
                break;
            }
            if (line.startsWith("检索：") || line.startsWith("检索:")) {
                var query = line.substring(3).strip().replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", "").strip();
                if (!query.isEmpty() && query.length() <= AnswerService.MAX_QUESTION_LENGTH) {
                    queries.add(query);
                }
            }
        }
        if (queries.isEmpty()) {
            return fallback(question, Outcome.FAILED, start);
        }
        return new Plan(List.copyOf(queries), false, Outcome.PLANNED, millisSince(start));
    }

    private static Plan fallback(String question, Outcome outcome, long start) {
        return new Plan(List.of(question), false, outcome, millisSince(start));
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
