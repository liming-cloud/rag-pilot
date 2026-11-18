package com.liming.rag.evaluation.application;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/** 一次运行。 */
public record StoredRun(long id, long setId, long runnerId, RunStatus status, OffsetDateTime startedAt,
        @Nullable OffsetDateTime finishedAt) {
}
