import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeft, Download } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { PageShell } from "@/components/ui/page-shell";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { PAGE_SIZE } from "@/lib/paging";
import { formatDateTime } from "@/lib/utils";
import { type Change, evaluationKeys, exportRun, fetchRun, MATCH_LABELS, type QuestionResult, type RunDetail } from "./api";

type Filter = "all" | "failed" | "NEWLY_FAILED" | "NEWLY_PASSED";

const FILTERS: { key: Filter; label: (run: RunDetail) => string }[] = [
  { key: "all", label: (run) => `全部 ${run.done}` },
  { key: "failed", label: (run) => `未通过 ${run.failed}` },
  { key: "NEWLY_FAILED", label: (run) => `新失败 ${run.newlyFailed}` },
  { key: "NEWLY_PASSED", label: (run) => `新通过 ${run.newlyPassed}` },
];

const CHANGES: Record<Change, { label: string; variant: "destructive" | "success" | "outline" } | null> = {
  NEWLY_FAILED: { label: "新失败", variant: "destructive" },
  NEWLY_PASSED: { label: "新通过", variant: "success" },
  NEW: { label: "新加的题", variant: "outline" },
  SAME: null,
};

/** 一次运行的结果：每道题的三个信号和与上一次运行的对比；默认先看新失败的题。结果可导出 CSV。 */
export function RunPage() {
  const runId = Number(useParams().runId);
  const [filter, setFilter] = useState<Filter | null>(null);
  const [page, setPage] = useState(1);
  const run = useQuery({
    queryKey: evaluationKeys.run(runId),
    queryFn: () => fetchRun(runId),
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? 1000 : false),
  });
  const download = useMutation({
    mutationFn: () => exportRun(runId),
    onSuccess: (blob) => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `知识质量-运行-${runId}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    },
  });
  const data = run.data;
  const active = filter ?? (data && data.newlyFailed > 0 ? "NEWLY_FAILED" : "all");
  const matched = data?.results.filter((result) => matches(result, active)) ?? [];
  const shown = matched.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  return (
    <PageShell>
      {data && (
        <Link
          to={`/admin/evaluation/sets/${data.setId}`}
          className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft aria-hidden="true" className="size-4" />
          {data.setName}
        </Link>
      )}
      <ErrorMessage error={run.error ?? download.error} className="text-base" />
      {data && (
        <>
          <div className="flex flex-wrap items-end gap-2">
            <div className="mr-auto">
              <h1 className="font-display text-2xl font-semibold tracking-tight">运行结果</h1>
              <p className="text-sm text-muted-foreground">
                {formatDateTime(data.startedAt)} · {data.runnerName}
                {data.status === "RUNNING" && ` · 运行中 ${data.done} / ${data.total}`}
                {data.status === "FAILED" && " · 运行中断，已跑完的题照常列出"}
              </p>
            </div>
            <Button size="sm" variant="outline" disabled={data.status === "RUNNING" || download.isPending} onClick={() => download.mutate()}>
              <Download aria-hidden="true" className="size-4" />
              导出
            </Button>
          </div>
          <Totals run={data} />
          <div className="flex flex-wrap gap-2">
            {FILTERS.map((item) => (
              <Button
                key={item.key}
                size="sm"
                variant={active === item.key ? "default" : "outline"}
                aria-pressed={active === item.key}
                onClick={() => {
                  setFilter(item.key);
                  setPage(1);
                }}
              >
                {item.label(data)}
              </Button>
            ))}
          </div>
          <ResultsTable results={shown} empty={data.results.length === 0 ? "还没有跑完的题" : "没有符合条件的题"} />
          <Pagination page={page} size={PAGE_SIZE} total={matched.length} onChange={(next) => setPage(next.page ?? 1)} />
        </>
      )}
    </PageShell>
  );
}

function matches(result: QuestionResult, filter: Filter) {
  if (filter === "all") return true;
  if (filter === "failed") return !result.passed;
  return result.change === filter;
}

function Totals({ run }: { run: RunDetail }) {
  const cards = [
    { label: "通过", value: `${run.passed} / ${run.total}` },
    { label: "未通过", value: String(run.failed) },
    { label: "新失败", value: String(run.newlyFailed), alert: run.newlyFailed > 0 },
    { label: "新通过", value: String(run.newlyPassed) },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      {cards.map((card) => (
        <div key={card.label} className="rounded-xl border bg-card p-4 shadow-sm">
          <div className="text-sm text-muted-foreground">{card.label}</div>
          <div className={card.alert ? "text-2xl font-semibold text-destructive tabular-nums" : "text-2xl font-semibold tabular-nums"}>
            {card.value}
          </div>
        </div>
      ))}
    </div>
  );
}

function ResultsTable({ results, empty }: { results: QuestionResult[]; empty: string }) {
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <Table aria-label="每道题的结果">
        <TableHeader>
          <TableRow>
            <TableHead>问题</TableHead>
            <TableHead className="w-28">结果</TableHead>
            <TableHead className="w-48">信号</TableHead>
            <TableHead className="w-80">回答</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {results.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="py-10 text-center text-muted-foreground">
                {empty}
              </TableCell>
            </TableRow>
          )}
          {results.map((result) => (
            <TableRow key={result.questionId}>
              <TableCell className="max-w-md min-w-56 align-top">
                <span className="line-clamp-3 wrap-anywhere" title={result.question}>
                  {result.question}
                </span>
                <span className="text-xs text-muted-foreground">{result.knowledgeBaseName}</span>
              </TableCell>
              <TableCell className="align-top">
                <div className="flex flex-col items-start gap-1">
                  <Badge variant={result.passed ? "success" : "destructive"}>{result.passed ? "通过" : "未通过"}</Badge>
                  {CHANGES[result.change] && (
                    <Badge variant={CHANGES[result.change]!.variant}>{CHANGES[result.change]!.label}</Badge>
                  )}
                </div>
              </TableCell>
              <TableCell className="align-top text-sm">
                <Signals result={result} />
              </TableCell>
              <TableCell className="max-w-80 align-top text-sm">
                <span className="line-clamp-3 wrap-anywhere" title={result.answerText}>
                  {result.answerText}
                </span>
                {result.citedDocuments.length > 0 && (
                  <span className="text-xs text-muted-foreground">引用：{result.citedDocuments.join("、")}</span>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/** 三个信号：应当拒答的题只看有没有拒答；期望文档的题看拒答、召回、引用，并列出没召回的文档。 */
function Signals({ result }: { result: QuestionResult }) {
  if (result.expectation === "REFUSAL") {
    return <span>应当拒答 · {result.refused ? "已拒答" : "没有拒答"}</span>;
  }
  return (
    <div className="flex flex-col gap-0.5">
      {result.refused && <span className="text-destructive">拒答了</span>}
      <span>
        召回 {result.recalledCount} / {result.expectedCount} · 引用 {result.citedCount} / {result.expectedCount}
      </span>
      {result.expectedCount > 1 && <span className="text-xs text-muted-foreground">{MATCH_LABELS[result.match]}</span>}
      {result.missingDocuments.length > 0 && (
        <span className="text-xs text-destructive" title={result.missingDocuments.join("、")}>
          没召回：{result.missingDocuments.join("、")}
        </span>
      )}
    </div>
  );
}
