import { Badge } from "@/components/ui/badge";
import { formatDateTime } from "@/lib/utils";
import type { RunSummary } from "./api";

/** 一次运行的结果摘要：进度或“通过 x / y”，和上一次比的新失败、新通过。 */
export function RunOutcome({ run, showTime = true }: { run: RunSummary; showTime?: boolean }) {
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-1.5 text-sm">
        {run.status === "RUNNING" && (
          <Badge>
            运行中 {run.done} / {run.total}
          </Badge>
        )}
        {run.status === "FAILED" && <Badge variant="destructive">运行失败</Badge>}
        {run.status === "DONE" && (
          <span className="tabular-nums">
            通过 <span className="font-medium">{run.passed}</span>
            <span className="text-muted-foreground"> / {run.total}</span>
          </span>
        )}
        {run.newlyFailed > 0 && <Badge variant="destructive">新失败 {run.newlyFailed}</Badge>}
        {run.newlyPassed > 0 && <Badge variant="success">新通过 {run.newlyPassed}</Badge>}
      </div>
      {showTime && (
        <div className="text-xs text-muted-foreground">
          {formatDateTime(run.startedAt)} · {run.runnerName}
        </div>
      )}
    </div>
  );
}
