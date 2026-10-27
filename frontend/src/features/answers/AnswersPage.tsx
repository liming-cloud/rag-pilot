import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { fetchUsers } from "@/features/users/api";
import { type AnswerReview, fetchAnswerReviews, type ReviewFilter, toEvalDrafts } from "./api";

const FILTERS: { value: ReviewFilter; label: string }[] = [
  { value: "disliked", label: "差评" },
  { value: "refused", label: "答不上来" },
  { value: "all", label: "全部" },
];

/**
 * 回答记录：管理员看员工点了“没帮助”的回答和答不上来的问题，据此补文档、改文档，或把问题加进评测集。
 */
export function AnswersPage() {
  const [filter, setFilter] = useState<ReviewFilter>("disliked");
  const reviews = useQuery({ queryKey: ["answer-reviews", filter], queryFn: () => fetchAnswerReviews(filter) });
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
        <Button
          size="sm"
          variant="ghost"
          className="ml-auto"
          disabled={!reviews.data?.length}
          onClick={() => download(`eval-drafts-${filter}.json`, toEvalDrafts(reviews.data ?? []))}
        >
          导出为评测题草稿
        </Button>
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
  return (
    <li className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <p className="font-medium">{review.question}</p>
        <span className="shrink-0 text-sm text-muted-foreground">
          {review.refused ? "答不上来" : review.rating === "DOWN" ? "没帮助" : review.rating === "UP" ? "有帮助" : "未评价"}
        </span>
      </div>
      <p className="text-sm text-muted-foreground">
        {asker} · {new Date(review.createdAt).toLocaleString("zh-CN")}
      </p>
      {!review.refused && <p className="text-sm leading-relaxed">{review.answerText}</p>}
      {review.comment && <p className="rounded-md bg-accent px-3 py-2 text-sm text-accent-foreground">{review.comment}</p>}
      {review.citedDocuments.length > 0 && (
        <p className="text-sm text-muted-foreground">引用：{review.citedDocuments.join("、")}</p>
      )}
    </li>
  );
}
