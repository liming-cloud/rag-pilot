import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Download, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { useSearchParams } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { OutcomeBadge, ReviewDialog } from "./ReviewDialog";
import {
  type AnswerFilter,
  answerKeys,
  type AnswerReview,
  fetchAllAnswerReviews,
  fetchAnswerReviews,
  toEvalDrafts,
} from "./api";

const PAGE_SIZE = 20;
const FILTERS: AnswerFilter["filter"][] = ["disliked", "refused", "all"];

/**
 * 回答记录（D-039）：按类型、处理状态、时间范围和问题关键词筛选，分页表格只显示一行摘要；点一行在弹窗里看完整的
 * 问答、留言和引用，并在弹窗里重新回答、标记已处理。默认看待处理的差评；从运营概览的“待处理”进来时
 * 地址带 ?filter=all，看全部待处理。
 */
export function AnswersPage() {
  const [params] = useSearchParams();
  const [filter, setFilter] = useState<AnswerFilter>({
    filter: FILTERS.find((value) => value === params.get("filter")) ?? "disliked",
    status: "open",
    days: 30,
    page: 1,
    size: PAGE_SIZE,
  });
  const [keyword, setKeyword] = useState("");
  const [opened, setOpened] = useState<AnswerReview | null>(null);
  const [exporting, setExporting] = useState(false);
  const reviews = useQuery({
    queryKey: answerKeys.reviewPage(filter),
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
        <Can permission={PERMISSIONS.ANSWER_EXPORT}>
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
      <ErrorMessage error={reviews.error} className="text-base" />
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
        <Pagination page={reviews.data.page} size={reviews.data.size} total={reviews.data.total} onChange={change} />
      )}
      <Dialog open={opened !== null} onOpenChange={(open) => !open && setOpened(null)}>
        {opened && <ReviewDialog review={opened} onDone={() => setOpened(null)} />}
      </Dialog>
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
