import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { type GapGroup, recheckGap, resolveGap } from "./api";

/**
 * 一类缺口的详情：类里的原始记录（时间、提问人、原文、类型、留言、状态）；用代表问题重新回答，确认补的资料生效后
 * 整类标记已处理。重新回答按管理员自己的权限检索、不写入记录、不通知原提问人（D-017）。
 */
export function GapDialog({ group, onDone }: { group: GapGroup; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState("");
  const open = group.records.filter((record) => record.reviewStatus === "OPEN");
  const recheck = useMutation({ mutationFn: () => recheckGap(group.id) });
  const resolve = useMutation({
    mutationFn: () =>
      resolveGap(
        open.map((record) => record.id),
        note.trim() || undefined,
      ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["answer-gaps"] });
      await queryClient.invalidateQueries({ queryKey: ["answer-reviews"] });
      onDone();
    },
  });

  return (
    <DialogContent className="max-w-3xl">
      <DialogHeader>
        <DialogTitle className="wrap-anywhere">{group.question}</DialogTitle>
        <DialogDescription>
          共 {group.count} 次，{group.phrasings} 种问法，待处理 {group.openCount} 条
          {group.knowledgeBases.length > 0 && ` · 检索过 ${group.knowledgeBases.join("、")}`}
          {group.citedDocuments.length > 0 && ` · 差评时引用了 ${group.citedDocuments.join("、")}`}
        </DialogDescription>
      </DialogHeader>
      <div className="max-h-72 overflow-y-auto rounded-lg border">
        <Table aria-label="这一类的记录">
          <TableHeader className="sticky top-0 bg-card">
            <TableRow>
              <TableHead className="w-28">时间</TableHead>
              <TableHead className="w-24">提问人</TableHead>
              <TableHead>问题</TableHead>
              <TableHead className="w-24">类型</TableHead>
              <TableHead className="w-20">状态</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {group.records.map((record) => (
              <TableRow key={record.id}>
                <TableCell className="whitespace-nowrap text-muted-foreground">
                  {new Date(record.createdAt).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" })}
                </TableCell>
                <TableCell>{record.askerName}</TableCell>
                <TableCell>
                  <div className="flex flex-col gap-0.5">
                    <span className="wrap-anywhere">{record.question}</span>
                    {record.knowledgeBases.length > 0 && (
                      <span className="text-xs text-muted-foreground wrap-anywhere">
                        检索：{record.knowledgeBases.join("、")}
                      </span>
                    )}
                    {record.comment && (
                      <span className="text-xs text-muted-foreground wrap-anywhere">留言：{record.comment}</span>
                    )}
                  </div>
                </TableCell>
                <TableCell>
                  {record.refused ? (
                    <Badge variant="destructive">答不上来</Badge>
                  ) : (
                    <Badge variant="outline">没帮助</Badge>
                  )}
                </TableCell>
                <TableCell>
                  {record.reviewStatus === "RESOLVED" ? (
                    <Badge variant="success">已处理</Badge>
                  ) : (
                    <Badge variant="outline">待处理</Badge>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {recheck.data && (
        <div role="status" className="flex flex-col gap-1 rounded-md border border-dashed p-3 text-sm">
          <span className="font-medium">现在的回答</span>
          <span>
            {recheck.data.refused
              ? "仍然答不上来，补的资料还没有覆盖这个问题。"
              : `${recheck.data.text}（引用 ${recheck.data.citations.map((citation) => citation.documentName).join("、")}）`}
          </span>
        </div>
      )}
      {(recheck.isError || resolve.isError) && (
        <p className="text-sm text-destructive">{(recheck.error ?? resolve.error)?.message}</p>
      )}
      {resolving && (
        <Label className="flex flex-col gap-1 text-sm">
          处理说明（选填，写进这 {open.length} 条记录）
          <Input
            value={note}
            maxLength={500}
            placeholder="如：已上传 2026 版营业时间规定"
            onChange={(event) => setNote(event.target.value)}
          />
        </Label>
      )}
      <DialogFooter>
        <Can permission={PERMISSIONS.ANSWER_RECHECK}>
          <Button size="sm" variant="ghost" disabled={recheck.isPending} onClick={() => recheck.mutate()}>
            {recheck.isPending ? "正在回答…" : "用代表问题重新回答"}
          </Button>
        </Can>
        {open.length > 0 && (
          <Can permission={PERMISSIONS.ANSWER_REVIEW}>
            {resolving ? (
              <Button size="sm" disabled={resolve.isPending} onClick={() => resolve.mutate()}>
                确认 {open.length} 条已处理
              </Button>
            ) : (
              <Button size="sm" variant="outline" onClick={() => setResolving(true)}>
                整类标记已处理
              </Button>
            )}
          </Can>
        )}
      </DialogFooter>
    </DialogContent>
  );
}
