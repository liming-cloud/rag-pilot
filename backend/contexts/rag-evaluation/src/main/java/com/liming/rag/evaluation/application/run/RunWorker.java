package com.liming.rag.evaluation.application.run;

import com.liming.rag.answering.api.AnswerProbe;
import com.liming.rag.evaluation.application.EvaluationUsage;
import com.liming.rag.evaluation.application.RunStatus;
import com.liming.rag.evaluation.application.RunStore;
import com.liming.rag.evaluation.application.StoredResult;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 后台逐题运行（#224）：按运行人的权限经 {@link AnswerProbe} 回答，用量记为“评测”；一道题跑完就存，页面能看到进度。
 * 题与题之间串行，不和员工问答抢模型的并发。模型不可用等失败时停在这道题，运行标为失败，已跑完的题照常保留。
 * 应用重启时上次没跑完的运行标为失败（不自动续跑，避免重复花费）。
 */
@Component
class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final RunStore runs;
    private final AnswerProbe probe;
    private final EvaluationUsage usage;
    private final AsyncTaskExecutor executor;
    private final Clock clock;

    RunWorker(RunStore runs, AnswerProbe probe, EvaluationUsage usage,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor, Clock clock) {
        this.runs = runs;
        this.probe = probe;
        this.usage = usage;
        this.executor = executor;
        this.clock = clock;
    }

    @TransactionalEventListener
    void on(RunStarted started) {
        executor.execute(() -> run(started));
    }

    @EventListener(ApplicationReadyEvent.class)
    void failUnfinished() {
        int failed = runs.failUnfinished(OffsetDateTime.now(clock));
        if (failed > 0) {
            log.warn("event=evaluation_run_abandoned runs={}", failed);
        }
    }

    void run(RunStarted started) {
        int passed = 0;
        var results = runs.results(started.runId());
        for (StoredResult result : results) {
            try {
                var question = result.question();
                var answer = usage.attribute(() -> probe.ask(started.tenantId(), started.runner(),
                        question.knowledgeBaseId(), question.question()));
                var outcome = Judge.judge(question, answer);
                runs.saveOutcome(started.runId(), question.id(), outcome);
                passed += outcome.passed() ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("event=evaluation_run_failed run={} error={}", started.runId(), e.getClass().getSimpleName());
                runs.finish(started.runId(), RunStatus.FAILED, OffsetDateTime.now(clock));
                return;
            }
        }
        runs.finish(started.runId(), RunStatus.DONE, OffsetDateTime.now(clock));
        log.info("event=evaluation_run_finished run={} questions={} passed={}", started.runId(), results.size(), passed);
    }
}
