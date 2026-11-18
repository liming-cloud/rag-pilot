package com.liming.rag.evaluation.application;

import com.liming.rag.evaluation.application.Viewers.Viewer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 一次运行里查看人能看到的题，每道题带上和上一次运行的对比（还没跑到的题 change 为 null）。 */
public record ComparedRun(StoredRun run, String runnerName, List<Compared> results) {

    public ComparedRun {
        results = List.copyOf(results);
    }

    public record Compared(StoredResult result, @Nullable Change change) {
    }

    public RunSummary summary() {
        var done = results.stream().filter(compared -> compared.result().outcome() != null).toList();
        int passed = (int) done.stream().filter(compared -> compared.result().outcome().passed()).count();
        return new RunSummary(run.id(), run.status(), results.size(), done.size(), passed, done.size() - passed,
                count(Change.NEWLY_FAILED), count(Change.NEWLY_PASSED), runnerName, run.startedAt(),
                run.finishedAt());
    }

    private int count(Change change) {
        return (int) results.stream().filter(compared -> compared.change() == change).count();
    }

    /** 查看人看得到的一道题 {@code result}，和上一次运行里同一道题的结果比。 */
    static @Nullable Change change(StoredResult result, @Nullable StoredResult before) {
        var outcome = result.outcome();
        if (outcome == null) {
            return null;
        }
        if (before == null || before.outcome() == null) {
            return Change.NEW;
        }
        if (before.outcome().passed() == outcome.passed()) {
            return Change.SAME;
        }
        return outcome.passed() ? Change.NEWLY_PASSED : Change.NEWLY_FAILED;
    }

    static List<StoredResult> visible(Viewer viewer, List<StoredResult> results) {
        return results.stream().filter(result -> viewer.sees(result.question())).toList();
    }
}
