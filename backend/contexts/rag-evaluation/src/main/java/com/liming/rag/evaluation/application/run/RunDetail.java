package com.liming.rag.evaluation.application.run;

import com.liming.rag.evaluation.application.RunStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 一次运行的结果页：概况（同 {@code RunSummary}）、题集，和查看人能看到的、已跑完的每道题。 */
public record RunDetail(long id, RunStatus status, int total, int done, int passed, int failed, int newlyFailed,
        int newlyPassed, String runnerName, OffsetDateTime startedAt, @Nullable OffsetDateTime finishedAt, long setId,
        String setName, List<ResultView> results) {
}
