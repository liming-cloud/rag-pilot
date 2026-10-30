import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Can } from "@/lib/permissions";
import {
  type AnswerFilter,
  type AnswerReview,
  fetchAllAnswerReviews,
  fetchAnswerReviews,
  markReview,
  recheckAnswer,
  toEvalDrafts,
} from "./api";

const PAGE_SIZE = 20;

/**
 * 回答记录（D-039）：按类型、处理状态、时间范围和问题关键词筛选，分页表格只显示一行摘要；点一行在弹窗里看完整的
 * 问答、留言和引用，并在弹窗里重新回答、标记已处理。默认看待处理的差评。
 */
export function AnswersPage() {
  const [filter, setFilter] = useState<AnswerFilter>({
    filter: "disliked",
    status: "open",
    days: 30,
    page: 1,
    size: PAGE_SIZE,
  });
  const [keyword, setKeyword] = useState("");
  const [opened, setOpened] = useState<AnswerReview | null>(null);
  const [exporting, setExporting] = useState(false);
  const reviews = useQuery({
    queryKey: ["answer-reviews", filter],
    queryFn: () => fetchAnswerReviews(filter),
    placeholderData: keepPreviousData,
  });

  const change = (next: Partial<AnswerFilter>) => setFilter({ ...filter, ...next, page: next.page ?? 1 });

  function search(event: FormEvent) {
    event.preventDefault();
    change({ keyword: keyword.trim() });
  }

  async function exportDrafts() {
    setExporting(true);
    try {
      download(`eval-drafts-${filter.filter}.json`, toEvalDrafts(await fetchAllAnswerReviews(filter)));
    } finally {
      setExporting(false);
    }
  }

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-4 p-6 md:p-8">
      <div className="flex items-center justify-between">
        <h1 className="font-serif text-2xl font-semibold">回答记录</h1>
        <Can permission="answer:export">
          <Button
            size="sm"
            variant="outline"
            disabled={!reviews.data?.total || exporting}
            onClick={() => void exportDrafts()}
          >
            <Download aria-hidden="true" className="size-4" />
            导出为评测题草稿
          </Button>
        </Can>
      </div>
      <form role="search" aria-label="筛选回答记录" onSubmit={search} className="flex flex-wrap items-end gap-2">
        <Label className="flex flex-col gap-1 text-sm">
          类型
          <NativeSelect
            value={filter.filter}
            onChange={(event) => change({ filter: event.target.value as AnswerFilter["filter"] })}
          >
            <NativeSelectOption value="disliked">没帮助</NativeSelectOption>
            <NativeSelectOption value="refused">答不上来</NativeSelectOption>
            <NativeSelectOption value="all">全部</NativeSelectOption>
          </NativeSelect>
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          处理状态
          <NativeSelect
            value={filter.status}
            onChange={(event) => change({ status: event.target.value as AnswerFilter["status"] })}
          >
            <NativeSelectOption value="open">待处理</NativeSelectOption>
            <NativeSelectOption value="resolved">已处理</NativeSelectOption>
            <NativeSelectOption value="all">全部</NativeSelectOption>
          </NativeSelect>
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          时间
          <NativeSelect
            value={filter.days ?? ""}
            onChange={(event) =>
              change({ days: event.target.value ? (Number(event.target.value) as AnswerFilter["days"]) : null })
            }
          >
            <NativeSelectOption value="7">最近 7 天</NativeSelectOption>
            <NativeSelectOption value="30">最近 30 天</NativeSelectOption>
            <NativeSelectOption value="90">最近 90 天</NativeSelectOption>
            <NativeSelectOption value="">不限</NativeSelectOption>
          </NativeSelect>
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          问题关键词
          <Input value={keyword} className="w-56" onChange={(event) => setKeyword(event.target.value)} />
        </Label>
        <Button type="submit" size="sm" variant="outline">
          <Search aria-hidden="true" className="size-4" />
          查询
        </Button>
      </form>
      <p className="text-xs text-muted-foreground">
        导出的是当前筛选条件下的全部记录（最多 1000
        条），要人工标注预期文档和答案关键词，放进仓库外的私有评测集（EVAL_DATA_DIR），不要提交到代码仓库。
      </p>
      {reviews.isError && <p className="text-destructive">{reviews.error.message}</p>}
      <div className="rounded-lg border bg-card">
        <Table aria-label="回答记录">
          <TableHeader>
            <TableRow>
              <TableHead className="w-36">时间</TableHead>
              <TableHead className="w-28">提问人</TableHead>
              <TableHead>问题</TableHead>
              <TableHead className="w-24">结果</TableHead>
              <TableHead className="w-20">状态</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {reviews.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-6 text-center text-muted-foreground">
                  没有符合条件的记录
                </TableCell>
              </TableRow>
            )}
            {reviews.data?.items.map((review) => (
              <TableRow
                key={review.id}
                tabIndex={0}
                aria-label={`查看：${review.question}`}
                className="cursor-pointer hover:bg-secondary"
                onClick={() => setOpened(review)}
                onKeyDown={(event) => event.key === "Enter" && setOpened(review)}
              >
                <TableCell className="py-2.5 text-muted-foreground">
                  {new Date(review.createdAt).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" })}
                </TableCell>
                <TableCell>{review.askerName}</TableCell>
                <TableCell className="max-w-md truncate">{review.question}</TableCell>
                <TableCell>
                  <OutcomeBadge review={review} />
                </TableCell>
                <TableCell>
                  {review.reviewStatus === "RESOLVED" ? (
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
      {reviews.data && (
        <Pagination
          page={reviews.data.page}
          size={reviews.data.size}
          total={reviews.data.total}
          onPageChange={(page) => change({ page })}
        />
      )}
      <Dialog open={opened !== null} onOpenChange={(open) => !open && setOpened(null)}>
        {opened && <ReviewDialog review={opened} onDone={() => setOpened(null)} />}
      </Dialog>
    </main>
  );
}

function OutcomeBadge({ review }: { review: AnswerReview }) {
  if (review.refused) return <Badge variant="destructive">答不上来</Badge>;
  if (review.rating === "DOWN") return <Badge variant="destructive">没帮助</Badge>;
  if (review.rating === "UP") return <Badge variant="success">有帮助</Badge>;
  return <Badge variant="outline">未评价</Badge>;
}

function download(filename: string, data: unknown) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

/** 一条记录的详情：完整问答、留言、引用；重新回答、标记已处理或重新打开。 */
function ReviewDialog({ review, onDone }: { review: AnswerReview; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState("");
  const recheck = useMutation({ mutationFn: () => recheckAnswer(review.id) });
  const mark = useMutation({
    mutationFn: ({ status, text }: { status: "RESOLVED" | "OPEN"; text?: string }) =>
      markReview(review.id, status, text),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["answer-reviews"] });
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
        <p className="text-sm text-destructive">{(recheck.error ?? mark.error)?.message}</p>
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
        <Can permission="answer:recheck">
          <Button size="sm" variant="ghost" disabled={recheck.isPending} onClick={() => recheck.mutate()}>
            重新回答
          </Button>
        </Can>
        <Can permission="answer:review">
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
