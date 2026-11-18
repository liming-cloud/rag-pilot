import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ErrorMessage } from "@/lib/errors";
import { evaluationKeys, fetchEstimate, type RunSummary, startRun } from "./api";

/** 运行前确认：题数、跳过的过期题和预估费用（D-069：先不设上限，只提示）。 */
export function RunDialog({ setId, onStarted }: { setId: number; onStarted: (run: RunSummary) => void }) {
  const queryClient = useQueryClient();
  const estimate = useQuery({ queryKey: evaluationKeys.estimate(setId), queryFn: () => fetchEstimate(setId) });
  const start = useMutation({
    mutationFn: () => startRun(setId),
    onSuccess: async (run) => {
      await queryClient.invalidateQueries({ queryKey: evaluationKeys.all });
      onStarted(run);
    },
  });
  const data = estimate.data;

  return (
    <DialogContent className="max-w-md">
      <DialogHeader>
        <DialogTitle>运行题集</DialogTitle>
        <DialogDescription>按你自己的权限逐题检索和回答，不写回答记录；用量记在“模型用量”的“评测”下。</DialogDescription>
      </DialogHeader>
      {data && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-foreground">参与运行</dt>
          <dd className="tabular-nums">{data.questions} 道题</dd>
          {data.skippedStale > 0 && (
            <>
              <dt className="text-muted-foreground">跳过</dt>
              <dd className="tabular-nums">{data.skippedStale} 道过期题</dd>
            </>
          )}
          <dt className="text-muted-foreground">预估费用</dt>
          <dd className="tabular-nums">
            {data.cost === null ? (
              <span className="text-muted-foreground">暂无法估算（没填单价或近 30 天没有回答用量）</span>
            ) : (
              <>
                约 {data.cost.toFixed(2)} {data.currency === "CNY" ? "元" : data.currency}
                <span className="text-muted-foreground">（按近 30 天每次回答的平均费用估算）</span>
              </>
            )}
          </dd>
        </dl>
      )}
      <ErrorMessage error={estimate.error ?? start.error} />
      <DialogFooter>
        <Button disabled={!data || data.questions === 0 || start.isPending} onClick={() => start.mutate()}>
          开始运行
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
