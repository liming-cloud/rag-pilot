import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { PAGE_SIZE } from "@/lib/paging";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { ModelName } from "./ModelLabels";
import { fetchModelChanges, type ModelChange, modelKeys, restoreChange, USAGE_LABELS } from "./api";

export function ChangesTab() {
  const [paging, setPaging] = useState({ page: 1, size: PAGE_SIZE });
  const [restoring, setRestoring] = useState<ModelChange | null>(null);
  const changes = useQuery({
    queryKey: modelKeys.changePage(paging),
    queryFn: () => fetchModelChanges(paging),
    placeholderData: keepPreviousData,
  });

  return (
    <section aria-label="变更记录" className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        每次保存和恢复都记一条。恢复会把该用途换回这条记录之后的模型，确认后先测连通，通过才生效。
      </p>
      <ErrorMessage error={changes.error} />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <Table aria-label="变更记录" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-32">时间</TableHead>
              <TableHead className="w-24">操作人</TableHead>
              <TableHead className="w-24">用途</TableHead>
              <TableHead>换成</TableHead>
              <TableHead>原来</TableHead>
              <TableHead className="w-16 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {changes.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-muted-foreground">
                  还没有变更
                </TableCell>
              </TableRow>
            )}
            {changes.data?.items.map((change) => (
              <TableRow key={change.id}>
                <TableCell className="text-muted-foreground">
                  {new Date(change.changedAt).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" })}
                </TableCell>
                <TableCell className="truncate">{change.changedBy}</TableCell>
                <TableCell>
                  {USAGE_LABELS[change.usage].name}
                  {change.restoredFrom !== null && (
                    <Badge variant="outline" className="ml-1">
                      恢复操作
                    </Badge>
                  )}
                </TableCell>
                <TableCell>
                  <div className="truncate text-xs text-muted-foreground">{change.after.providerName}</div>
                  <ModelName model={change.after.model} />
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {change.before ? (
                    <>
                      <div className="truncate text-xs">{change.before.providerName}</div>
                      <ModelName model={change.before.model} />
                    </>
                  ) : (
                    "—"
                  )}
                </TableCell>
                <TableCell className="text-right">
                  <Can permission={PERMISSIONS.MODEL_UPDATE}>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`恢复${USAGE_LABELS[change.usage].name}为 ${change.after.model}`}
                      onClick={() => setRestoring(change)}
                    >
                      恢复
                    </Button>
                  </Can>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {changes.data && (
        <Pagination page={changes.data.page} size={changes.data.size} total={changes.data.total} onChange={setPaging} />
      )}
      <Dialog open={restoring !== null} onOpenChange={(open) => !open && setRestoring(null)}>
        {restoring && <RestoreDialog change={restoring} onDone={() => setRestoring(null)} />}
      </Dialog>
    </section>
  );
}

/** 恢复前确认：写明会把哪个用途换回哪个模型。 */
export function RestoreDialog({ change, onDone }: { change: ModelChange; onDone: () => void }) {
  const queryClient = useQueryClient();
  const label = USAGE_LABELS[change.usage].name;
  const restore = useMutation({
    mutationFn: () => restoreChange(change.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: modelKeys.settings });
      await queryClient.invalidateQueries({ queryKey: modelKeys.changes });
      onDone();
    },
  });

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>恢复{label}模型</DialogTitle>
        <DialogDescription>
          把{label}换回 {change.after.providerName} / {change.after.model}。会先测连通，通过后立即生效，并记一条变更。
        </DialogDescription>
      </DialogHeader>
      <ErrorMessage error={restore.error} />
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        <Button type="button" size="sm" disabled={restore.isPending} onClick={() => restore.mutate()}>
          {restore.isPending ? "测试中…" : "确认恢复"}
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
