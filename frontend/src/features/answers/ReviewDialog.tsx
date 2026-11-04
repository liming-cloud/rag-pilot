import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { answerKeys, type AnswerReview, markReview, recheckAnswer } from "./api";

export function OutcomeBadge({ review }: { review: AnswerReview }) {
  if (review.refused) return <Badge variant="destructive">答不上来</Badge>;
  if (review.rating === "DOWN") return <Badge variant="destructive">没帮助</Badge>;
  if (review.rating === "UP") return <Badge variant="success">有帮助</Badge>;
  return <Badge variant="outline">未评价</Badge>;
}

/** 一条记录的详情：完整问答、留言、引用；重新回答、标记已处理或重新打开。 */
export function ReviewDialog({ review, onDone }: { review: AnswerReview; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState("");
  const recheck = useMutation({ mutationFn: () => recheckAnswer(review.id) });
  const mark = useMutation({
    mutationFn: ({ status, text }: { status: "RESOLVED" | "OPEN"; text?: string }) =>
      markReview(review.id, status, text),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: answerKeys.reviews });
      onDone();
    },
  });
  const resolved = review.reviewStatus === "RESOLVED";

  return (
    <DialogContent className="max-w-2xl">
      <DialogHeader>
        <DialogTitle>{review.question}</DialogTitle>
        <DialogDescription>
          {review.askerName} · {new Date(review.createdAt).toLocaleString("zh-CN")}
        </DialogDescription>
      </DialogHeader>
      <div className="flex items-center gap-2">
        <OutcomeBadge review={review} />
        {resolved && <Badge variant="success">已处理</Badge>}
      </div>
      {review.rewrittenQuestion && (
        <p className="text-sm text-muted-foreground">系统改写为：{review.rewrittenQuestion}</p>
      )}
      {!review.refused && <p className="text-sm leading-relaxed">{review.answerText}</p>}
      {review.comment && (
        <p className="rounded-md bg-accent px-3 py-2 text-sm text-accent-foreground">员工留言：{review.comment}</p>
      )}
      {review.citedDocuments.length > 0 && (
        <p className="text-sm text-muted-foreground">引用：{review.citedDocuments.join("、")}</p>
      )}
      {resolved && review.resolutionNote && <p className="text-sm text-success">处理说明：{review.resolutionNote}</p>}
      {recheck.data && (
        <div className="flex flex-col gap-1 rounded-md border border-dashed p-3 text-sm">
          <span className="font-medium">现在的回答</span>
          <span>{recheck.data.refused ? "仍然答不上来。" : recheck.data.text}</span>
        </div>
      )}
      {(recheck.isError || mark.isError) && (
        <ErrorMessage error={recheck.error ?? mark.error} />
      )}
      {resolving && (
        <Label className="flex flex-col gap-1 text-sm">
          处理说明（选填）
          <Input
            value={note}
            maxLength={500}
            placeholder="如：已上传 2026 版"
            onChange={(event) => setNote(event.target.value)}
          />
        </Label>
      )}
      <DialogFooter>
        <Can permission={PERMISSIONS.ANSWER_RECHECK}>
          <Button size="sm" variant="ghost" disabled={recheck.isPending} onClick={() => recheck.mutate()}>
            重新回答
          </Button>
        </Can>
        <Can permission={PERMISSIONS.ANSWER_REVIEW}>
          {resolved ? (
            <Button size="sm" variant="outline" onClick={() => mark.mutate({ status: "OPEN" })}>
              重新打开
            </Button>
          ) : resolving ? (
            <Button
              size="sm"
              disabled={mark.isPending}
              onClick={() => mark.mutate({ status: "RESOLVED", text: note.trim() || undefined })}
            >
              确认已处理
            </Button>
          ) : (
            <Button size="sm" variant="outline" onClick={() => setResolving(true)}>
              标记已处理
            </Button>
          )}
        </Can>
      </DialogFooter>
    </DialogContent>
  );
}
