package com.liming.rag.evaluation.application;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 运行记录和每道题结果的存储端口（#224）。 */
public interface RunStore {

    /**
     * 开始一次运行：写入运行记录和每道题的待跑结果（题目、期望的快照）。同一个题集已有运行中的记录时为空。
     */
    Optional<StoredRun> start(TenantId tenantId, long setId, UserId runner, List<GoldenQuestion> questions,
            OffsetDateTime now);

    void saveOutcome(long runId, long questionId, QuestionOutcome outcome);

    void finish(long runId, RunStatus status, OffsetDateTime now);

    /** 应用启动时把上次进程里没跑完的运行标为失败，返回条数。 */
    int failUnfinished(OffsetDateTime now);

    Optional<StoredRun> run(TenantId tenantId, long runId);

    /** 题集的运行记录，新的在前。 */
    List<StoredRun> runs(TenantId tenantId, long setId);

    /** 每个题集最近的一次运行。 */
    Map<Long, StoredRun> latestRuns(TenantId tenantId);

    /** 同一题集里在这次之前的最近一次运行。 */
    Optional<StoredRun> previous(StoredRun run);

    /** 这次运行每道题的结果，按开始时的顺序。 */
    List<StoredResult> results(long runId);
}
