package com.liming.rag.evaluation.application;

import com.liming.rag.evaluation.application.ComparedRun.Compared;
import com.liming.rag.evaluation.application.Viewers.Viewer;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.identity.api.UserDirectory.UserSummary;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 按查看人汇总一次运行并和上一次比（#224）：题集列表的“最近一次运行”和运行结果页共用同一个口径。 */
@Component
public class RunComparisons {

    private final RunStore runs;
    private final UserDirectory users;

    RunComparisons(RunStore runs, UserDirectory users) {
        this.runs = runs;
        this.users = users;
    }

    public ComparedRun compare(Viewer viewer, StoredRun run) {
        var results = ComparedRun.visible(viewer, runs.results(run.id()));
        Map<Long, StoredResult> before = runs.previous(run)
                .map(previous -> runs.results(previous.id()).stream()
                        .collect(Collectors.toMap(result -> result.question().id(), Function.identity())))
                .orElse(Map.of());
        List<Compared> compared = results.stream()
                .map(result -> new Compared(result, ComparedRun.change(result, before.get(result.question().id()))))
                .toList();
        return new ComparedRun(run, runnerName(viewer, run), compared);
    }

    private String runnerName(Viewer viewer, StoredRun run) {
        var summary = users.summaries(viewer.tenantId(), List.of(run.runnerId())).get(run.runnerId());
        return summary == null ? "" : displayName(summary);
    }

    private static String displayName(UserSummary summary) {
        return summary.displayName().isBlank() ? summary.username() : summary.displayName();
    }
}
