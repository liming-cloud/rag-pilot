import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Can } from "@/lib/permissions";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { fetchUsers } from "@/features/users/api";
import {
  type AnswerReview,
  fetchAnswerReviews,
  markReview,
  recheckAnswer,
  type ReviewFilter,
  toEvalDrafts,
} from "./api";

const FILTERS: { value: ReviewFilter; label: string }[] = [
  { value: "disliked", label: "差评" },
  { value: "refused", label: "答不上来" },
  { value: "all", label: "全部" },
];

/**
 * 回答记录：管理员看员工点了“没帮助”的回答和答不上来的问题，据此补文档、改文档，或把问题加进评测集。
 * 补完文档后点“重新回答”确认问题已解决，再标记已处理；列表默认只显示待处理的。
 */
export function AnswersPage() {
  const [filter, setFilter] = useState<ReviewFilter>("disliked");
  const [includeResolved, setIncludeResolved] = useState(false);
  const reviews = useQuery({
    queryKey: ["answer-reviews", filter, includeResolved],
    queryFn: () => fetchAnswerReviews(filter, includeResolved),
  });
  const users = useQuery({ queryKey: ["users"], queryFn: fetchUsers });
  const nameOf = (userId: number) => users.data?.find((user) => user.id === userId)?.displayName ?? `用户 ${userId}`;

  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-4 p-6 md:p-8">
      <h1 className="font-serif text-2xl font-semibold">回答记录</h1>
      <div className="flex flex-wrap gap-2">
        {FILTERS.map((option) => (
          <Button
            key={option.value}
            size="sm"
            variant={filter === option.value ? "default" : "outline"}
            aria-pressed={filter === option.value}
            onClick={() => setFilter(option.value)}
          >
            {option.label}
          </Button>
        ))}
        <Label className="flex items-center gap-1">
          <Checkbox checked={includeResolved} onCheckedChange={(checked) => setIncludeResolved(checked === true)} />
          包含已处理
        </Label>
        <Can permission="answer:export">
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto"
            disabled={!reviews.data?.length}
            onClick={() => download(`eval-drafts-${filter}.json`, toEvalDrafts(reviews.data ?? []))}
          >
            导出为评测题草稿
          </Button>
        </Can>
      </div>
      <p className="text-sm text-muted-foreground">
        导出的题要人工标注预期文档和答案关键词，放进仓库外的私有评测集（EVAL_DATA_DIR），不要提交到代码仓库。
      </p>
      {reviews.isError && <p className="text-destructive">{reviews.error.message}</p>}
      {reviews.data?.length === 0 && <p className="text-muted-foreground">暂时没有记录。</p>}
      <ul aria-label="回答记录" className="flex flex-col gap-3">
        {reviews.data?.map((review) => (
          <ReviewItem key={review.id} review={review} asker={nameOf(review.userId)} />
        ))}
      </ul>
    </main>
  );
}

function download(filename: string, data: unknown) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function ReviewItem({ review, asker }: { review: AnswerReview; asker: string }) {
  const queryClient = useQueryClient();
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState("");
  const recheck = useMutation({ mutationFn: () => recheckAnswer(review.id) });
  const review_ = useMutation({
    mutationFn: ({ status, text }: { status: "RESOLVED" | "OPEN"; text?: string }) =>
      markReview(review.id, status, text),
    onSuccess: async () => {
      setResolving(false);
      setNote("");
      await queryClient.invalidateQueries({ queryKey: ["answer-reviews"] });
    },
  });
  const resolved = review.reviewStatus === "RESOLVED";

  return (
    <li className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <p className="font-medium">{review.question}</p>
        <span className="shrink-0 text-sm text-muted-foreground">
          {review.refused
            ? "答不上来"
            : review.rating === "DOWN"
              ? "没帮助"
              : review.rating === "UP"
                ? "有帮助"
                : "未评价"}
        </span>
      </div>
      <p className="text-sm text-muted-foreground">
        {asker} · {new Date(review.createdAt).toLocaleString("zh-CN")}
      </p>
      {review.rewrittenQuestion && (
        <p className="text-sm text-muted-foreground">系统改写为：{review.rewrittenQuestion}</p>
      )}
      {!review.refused && <p className="text-sm leading-relaxed">{review.answerText}</p>}
      {review.comment && (
        <p className="rounded-md bg-accent px-3 py-2 text-sm text-accent-foreground">{review.comment}</p>
      )}
      {review.citedDocuments.length > 0 && (
        <p className="text-sm text-muted-foreground">引用：{review.citedDocuments.join("、")}</p>
      )}
      {resolved && (
        <p className="text-sm text-success">已处理{review.resolutionNote ? `：${review.resolutionNote}` : ""}</p>
      )}
      {recheck.data && (
        <div className="flex flex-col gap-1 rounded-md border border-dashed p-3 text-sm">
          <span className="font-medium">现在的回答</span>
          <span>{recheck.data.refused ? "仍然答不上来。" : recheck.data.text}</span>
        </div>
      )}
      {(recheck.isError || review_.isError) && (
        <p className="text-sm text-destructive">{(recheck.error ?? review_.error)?.message}</p>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Can permission="answer:recheck">
          <Button size="sm" variant="ghost" disabled={recheck.isPending} onClick={() => recheck.mutate()}>
            重新回答
          </Button>
        </Can>
        <Can permission="answer:review">
          {resolved ? (
            <Button size="sm" variant="outline" onClick={() => review_.mutate({ status: "OPEN" })}>
              重新打开
            </Button>
          ) : resolving ? (
            <form
              className="flex items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                review_.mutate({ status: "RESOLVED", text: note.trim() || undefined });
              }}
            >
              <Label className="sr-only" htmlFor={`note-${review.id}`}>
                处理说明（选填）
              </Label>
              <Input
                id={`note-${review.id}`}
                value={note}
                maxLength={500}
                onChange={(event) => setNote(event.target.value)}
                placeholder="处理说明（选填），如：已上传 2026 版"
                className="w-56"
              />
              <Button type="submit" size="sm" disabled={review_.isPending}>
                确认
              </Button>
              <Button type="button" size="sm" variant="ghost" onClick={() => setResolving(false)}>
                取消
              </Button>
            </form>
          ) : (
            <Button size="sm" variant="outline" onClick={() => setResolving(true)}>
              标记已处理
            </Button>
          )}
        </Can>
      </div>
    </li>
  );
}
