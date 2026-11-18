import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { ErrorMessage } from "@/lib/errors";
import { RunOutcome } from "./RunOutcome";
import { evaluationKeys, fetchRuns } from "./api";

/** 题集的历次运行，新的在前；有运行中的就每 2 秒刷新进度。 */
export function RunsTable({ setId }: { setId: number }) {
  const runs = useQuery({
    queryKey: evaluationKeys.runs(setId),
    queryFn: () => fetchRuns(setId),
    refetchInterval: (query) => (query.state.data?.some((run) => run.status === "RUNNING") ? 2000 : false),
  });

  return (
    <section aria-label="运行记录" className="flex flex-col gap-2">
      <h2 className="font-medium">运行记录</h2>
      <ErrorMessage error={runs.error} />
      {runs.data?.length === 0 && <p className="text-sm text-muted-foreground">还没运行过。</p>}
      <ol className="flex flex-col gap-2">
        {runs.data?.map((run) => (
          <li key={run.id}>
            <Link
              to={`/admin/evaluation/runs/${run.id}`}
              className="block rounded-xl border bg-card p-3 shadow-sm hover:bg-secondary"
              aria-label={`查看运行结果：${run.startedAt}`}
            >
              <RunOutcome run={run} />
            </Link>
          </li>
        ))}
      </ol>
    </section>
  );
}
