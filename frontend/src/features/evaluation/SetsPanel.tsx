import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { formatDateTime } from "@/lib/utils";
import { RunOutcome } from "./RunOutcome";
import { SetDialog } from "./SetDialog";
import { type EvalSet, evaluationKeys, fetchSets } from "./api";

/** 题集列表：题数只数当前账号能访问的库里的题；点一行进入题集。 */
export function SetsPanel() {
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const sets = useQuery({ queryKey: evaluationKeys.sets, queryFn: fetchSets });

  return (
    <>
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          一个题集可以包含多个知识库的题；你无权访问的库里的题不显示、不运行、不计分。
        </p>
        <Can permission={PERMISSIONS.EVAL_MANAGE}>
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus aria-hidden="true" className="size-4" />
            新建题集
          </Button>
        </Can>
      </div>
      <ErrorMessage error={sets.error} className="text-base" />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <Table aria-label="题集">
          <TableHeader>
            <TableRow>
              <TableHead>题集</TableHead>
              <TableHead className="w-24 text-right">标准题</TableHead>
              <TableHead className="w-56">知识库</TableHead>
              <TableHead className="w-72">最近一次运行</TableHead>
              <TableHead className="w-40">更新时间</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sets.data?.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-10 text-center text-muted-foreground">
                  还没有题集。先新建一个，再从“试问”、CSV 或回答记录里添加标准题。
                </TableCell>
              </TableRow>
            )}
            {sets.data?.map((set) => (
              <SetRow key={set.id} set={set} onOpen={() => void navigate(`/admin/evaluation/sets/${set.id}`)} />
            ))}
          </TableBody>
        </Table>
      </div>
      <Dialog open={creating} onOpenChange={setCreating}>
        {creating && (
          <SetDialog set={null} onDone={(saved) => void navigate(`/admin/evaluation/sets/${saved.id}`)} />
        )}
      </Dialog>
    </>
  );
}

function SetRow({ set, onOpen }: { set: EvalSet; onOpen: () => void }) {
  return (
    <TableRow
      tabIndex={0}
      aria-label={`打开题集：${set.name}`}
      className="cursor-pointer hover:bg-secondary"
      onClick={onOpen}
      onKeyDown={(event) => event.key === "Enter" && onOpen()}
    >
      <TableCell className="max-w-md min-w-56">
        <div className="font-medium wrap-anywhere">{set.name}</div>
        {set.description && <div className="line-clamp-2 text-sm text-muted-foreground">{set.description}</div>}
      </TableCell>
      <TableCell className="text-right whitespace-nowrap tabular-nums">
        {set.questionCount}
        {set.staleCount > 0 && <div className="text-xs text-destructive">过期 {set.staleCount}</div>}
      </TableCell>
      <TableCell className="max-w-56 truncate text-muted-foreground" title={set.knowledgeBaseNames.join("、")}>
        {namesLabel(set.knowledgeBaseNames)}
      </TableCell>
      <TableCell>{set.lastRun ? <RunOutcome run={set.lastRun} /> : <span className="text-muted-foreground">还没运行过</span>}</TableCell>
      <TableCell className="whitespace-nowrap text-muted-foreground">{formatDateTime(set.updatedAt)}</TableCell>
    </TableRow>
  );
}

/** 最多列两个库，其余用“等 N 个”。 */
export function namesLabel(names: string[]) {
  if (names.length === 0) return "—";
  return names.length <= 2 ? names.join("、") : `${names.slice(0, 2).join("、")} 等 ${names.length} 个`;
}
