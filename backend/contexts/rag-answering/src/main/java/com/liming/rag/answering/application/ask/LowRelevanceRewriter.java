package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.domain.Question;
import com.liming.rag.settings.api.IndustryPack;
import com.liming.rag.answering.application.ask.Rewrite.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * 把员工的问题改写成规范说法，供“低分时再检索一次”使用。改写规则（策略）在这里，模型调用经端口
 * {@link QuestionRewriting}（输出限一句话，D-054）。
 *
 * <p>规则只写通用的，不针对任何评测题。超过时限就放弃。模型没给出有效改写（空、NO_ANSWER、太长、和原问题一样）时
 * 返回原问题。
 */
final class LowRelevanceRewriter {

    /** 内核的改写规则；行业包的提示排在“改成书面说法”之后（D-045）。 */
    private static final List<String> LEADING_RULES = List.of("纠正错别字和同音字。", "把口语、方言、简称改成书面说法。");
    private static final List<String> TRAILING_RULES = List.of("不要回答问题，不要添加原问题里没有的条件。",
            "只输出改写后的一句问题，不要解释。");

    /** 一次改写的结果；{@code question} 是改写后的问题，没改写时是原问题。 */
    record Result(String question, Outcome outcome, int outputChars, long modelMillis) {
    }

    private final QuestionRewriting rewriting;
    private final Duration timeout;
    private final String system;
    /** 每次改写在这里另起一个虚拟线程，超时后不再等它。 */
    private final AsyncTaskExecutor calls;

    LowRelevanceRewriter(QuestionRewriting rewriting, Duration timeout, IndustryPack pack, AsyncTaskExecutor calls) {
        this.rewriting = rewriting;
        this.timeout = timeout;
        this.system = system(pack);
        this.calls = calls;
    }

    /** 改写的系统提示词：行业包给开头的角色和行业提示，其余规则是内核的。 */
    static String system(IndustryPack pack) {
        var rules = new ArrayList<String>(LEADING_RULES);
        rules.addAll(pack.rewriteHints());
        rules.addAll(TRAILING_RULES);
        var text = new StringBuilder(pack.rewriteRole()).append("\n规则：\n");
        for (int i = 0; i < rules.size(); i++) {
            text.append(i + 1).append(". ").append(rules.get(i)).append('\n');
        }
        return text.toString();
    }

    Result rewrite(String question) {
        long start = System.nanoTime();
        var call = calls.submit(() -> rewriting.rewrite(system, question));
        String reply;
        try {
            reply = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            return new Result(question, Outcome.TIMED_OUT, 0, millisSince(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(question, Outcome.TIMED_OUT, 0, millisSince(start));
        } catch (ExecutionException e) {
            // 改写失败不影响拒答：模型不可用时按没改写处理。
            return new Result(question, Outcome.NO_CHANGE, 0, millisSince(start));
        }
        long modelMillis = millisSince(start);
        var raw = reply == null ? "" : reply;
        var rewritten = raw.strip().lines().findFirst().orElse("").strip()
                .replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", "");
        if (rewritten.isEmpty() || rewritten.startsWith(AnswerPrompt.NO_ANSWER)
                || !Question.fits(rewritten) || rewritten.equals(question)) {
            return new Result(question, Outcome.NO_CHANGE, raw.length(), modelMillis);
        }
        return new Result(rewritten, Outcome.REWRITTEN, raw.length(), modelMillis);
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
