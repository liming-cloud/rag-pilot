package com.liming.rag.evaluation.application;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 一次运行的概况，只数查看人能看到的题（D-070）。
 *
 * @param total 参与运行的题数（跳过的过期题不算）
 * @param done 已跑完的题数
 */
public record RunSummary(long id, RunStatus status, int total, int done, int passed, int failed, int newlyFailed,
        int newlyPassed, String runnerName, OffsetDateTime startedAt, @Nullable OffsetDateTime finishedAt) {
}
