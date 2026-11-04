import { useMutation, useQuery } from "@tanstack/react-query";
import { ChevronRight, Download, Printer } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchExpirySummary } from "@/features/knowledge/api";
import { Can, PERMISSIONS, useCan } from "@/lib/permissions";
import {
  exportUsageCsv,
  fetchUsageStats,
  RANGES,
  recentWeeks,
  type StatsPeriod,
  type Totals,
  type UsageStats,
  zoneLabel,
} from "./api";

const REASONS: Record<string, string> = {
  NO_EVIDENCE: "知识库里没有内容",
  LOW_RELEVANCE: "相关度低于门槛",
  MODEL_DECLINED: "模型判断资料不足",
  UNCITED: "回答没有可核对的引用",
};

/** 运营概览上列出的到期资料份数，其余到知识库里看。 */
const EXPIRY_ROWS = 5;

/**
 * 运营概览（管理端首页）：员工用得怎么样、哪些资料该补、哪些资料该更新，供试点汇报和补文档参考。
 * 可以看最近 N 天，也可以看某个自然周并与上一周对比（#125、D-047）；“打印”用浏览器存成 PDF 作为周报，
 * “导出明细”下载这段时间每条问答的 CSV（stats:export）。打印时隐藏菜单和按钮。
 * 有回答记录权限时，“待处理”链到待处理的回答记录，知识缺口链到知识缺口页。
 * 比例在前端算：拒答率的分母是提问数，评价率和差评率的分母是给出了回答的题数。
 */
export function StatsPage() {
  const [period, setPeriod] = useState<StatsPeriod>({ days: 30 });
  const stats = useQuery({ queryKey: ["usage-stats", period], queryFn: () => fetchUsageStats(period) });
  const weeks = recentWeeks(stats.data?.zone ?? "Asia/Shanghai");
  const week = "week" in period ? period.week : "";
  const exporting = useMutation({
    mutationFn: () => exportUsageCsv(period),
    onSuccess: ({ filename, blob }) => download(filename, blob),
  });

  return (
    <main className="mx-auto flex max-w-4xl flex-col gap-6 p-6 md:p-8 print:max-w-none print:p-0">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto font-serif text-2xl font-semibold">{week ? "运营周报" : "运营概览"}</h1>
        <div className="flex flex-wrap items-center gap-2 print:hidden">
          {RANGES.map((range) => (
            <Button
              key={range}
              size="sm"
              variant={"days" in period && period.days === range ? "default" : "outline"}
              aria-pressed={"days" in period && period.days === range}
              onClick={() => setPeriod({ days: range })}
            >
              近 {range} 天
            </Button>
          ))}
          <NativeSelect
            aria-label="按周查看"
            value={week}
            onChange={(event) => setPeriod(event.target.value ? { week: event.target.value } : { days: 30 })}
          >
            <NativeSelectOption value="">按周查看…</NativeSelectOption>
            {weeks.map((item, index) => (
              <NativeSelectOption key={item.start} value={item.start}>
                {weekLabel(item.start, item.end)}
                {index === 0 ? "（本周）" : index === 1 ? "（上周）" : ""}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2 print:hidden">
        <Button size="sm" variant="outline" disabled={!stats.data} onClick={() => window.print()}>
          <Printer aria-hidden="true" className="size-4" />
          打印 / 存为 PDF
        </Button>
        <Can permission={PERMISSIONS.STATS_EXPORT}>
          <Button
            size="sm"
            variant="outline"
            disabled={!stats.data || exporting.isPending}
            onClick={() => exporting.mutate()}
          >
            <Download aria-hidden="true" className="size-4" />
            导出问答明细（CSV）
          </Button>
        </Can>
        {exporting.isError && <span className="text-sm text-destructive">{exporting.error.message}</span>}
      </div>
      {stats.isPending && <p className="text-muted-foreground">正在统计…</p>}
      {stats.isError && <p className="text-destructive">{stats.error.message}</p>}
      {stats.data && <StatsView stats={stats.data} />}
    </main>
  );
}

function weekLabel(start: string, end: string) {
  const short = (date: string) => `${Number(date.slice(5, 7))}月${Number(date.slice(8))}日`;
  return `${short(start)}–${short(end)}`;
}

function download(filename: string, blob: Blob) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

type Card = { label: string; value: string; note: string; change?: string; to?: string };

function StatsView({ stats }: { stats: UsageStats }) {
  const { totals, previous } = stats;
  const canReview = useCan(PERMISSIONS.ANSWER_VIEW);
  const answered = totals.questions - totals.refused;
  const cards: Card[] = [
    {
      label: "提问",
      value: String(totals.questions),
      note: `${totals.askers} 位员工`,
      change: previous ? countChange(totals.questions, previous.questions) : undefined,
    },
    {
      label: "拒答率",
      value: percent(totals.refused, totals.questions),
      note: `${totals.refused} 题答不上来`,
      change: previous ? rateChange(totals, previous, (t) => [t.refused, t.questions]) : undefined,
    },
    {
      label: "评价率",
      value: percent(totals.rated, answered),
      note: `已回答 ${answered} 题中有评价的`,
    },
    {
      label: "好评率",
      value: percent(totals.liked, totals.rated),
      note: `${totals.rated} 个评价里 ${totals.liked} 个好评`,
      change: previous ? rateChange(totals, previous, (t) => [t.liked, t.rated]) : undefined,
    },
    {
      label: "差评率",
      value: percent(totals.disliked, answered),
      note: `已回答 ${answered} 题中 ${totals.disliked} 个差评`,
      change: previous ? rateChange(totals, previous, (t) => [t.disliked, t.questions - t.refused]) : undefined,
    },
    {
      label: "待处理",
      value: String(totals.openReviews),
      note: "差评和答不上来，未标记已处理",
      to: canReview ? "/admin/answers?filter=all" : undefined,
    },
  ];

  return (
    <>
      <p className="text-sm text-muted-foreground">
        {stats.from} 至 {stats.to}（{zoneLabel(stats.zone)}）
        {previous && (stats.daily.length < 7 ? "，本周还没过完，与上周同期对比" : "，与上一周对比")}
      </p>
      <ul aria-label="汇总" className="grid grid-cols-2 gap-3 md:grid-cols-3 print:grid-cols-3">
        {cards.map((card) => (
          <SummaryCard key={card.label} card={card} />
        ))}
      </ul>
      <ExpirySection />
      {totals.questions === 0 ? (
        <p className="text-muted-foreground">这段时间还没有提问。</p>
      ) : (
        <>
          <DailyTable daily={stats.daily} />
          <div className="grid gap-6 md:grid-cols-2 print:grid-cols-2">
            <Ranking
              title="知识缺口（待处理最多）"
              to={canReview ? "/admin/gaps" : undefined}
              rows={stats.topGaps.map((row) => ({ label: row.question, count: row.count }))}
            />
            <Ranking
              title="拒答原因"
              rows={stats.refusalReasons.map((row) => ({ label: REASONS[row.reason] ?? row.reason, count: row.count }))}
            />
            <Ranking
              title="被引用最多的文档"
              rows={stats.topCitedDocuments.map((row) => ({ label: row.documentName, count: row.count }))}
            />
          </div>
        </>
      )}
    </>
  );
}

function SummaryCard({ card }: { card: Card }) {
  const body = (
    <>
      <span className="flex items-center text-sm text-muted-foreground">
        {card.label}
        {card.to && <ChevronRight aria-hidden="true" className="ml-auto size-4 print:hidden" />}
      </span>
      <span className="text-2xl font-semibold">{card.value}</span>
      <span className="text-xs text-muted-foreground">{card.note}</span>
      {card.change && <span className="text-xs">{card.change}</span>}
    </>
  );
  return (
    <li className="flex rounded-lg border bg-card print:break-inside-avoid">
      {card.to ? (
        <Link
          to={card.to}
          aria-label={`${card.label} ${card.value}，去处理`}
          className="flex flex-1 flex-col gap-1 rounded-lg p-3 hover:bg-secondary"
        >
          {body}
        </Link>
      ) : (
        <div className="flex flex-1 flex-col gap-1 p-3">{body}</div>
      )}
    </li>
  );
}

function countChange(current: number, previous: number) {
  const delta = current - previous;
  return `比上周 ${delta > 0 ? "+" : ""}${delta}`;
}

/** 比例的变化用百分点表示；上周没有分母时不比较。 */
function rateChange(current: Totals, previous: Totals, parts: (totals: Totals) => [number, number]) {
  const [part, whole] = parts(current);
  const [previousPart, previousWhole] = parts(previous);
  if (whole === 0 || previousWhole === 0) return "上周无可比数据";
  const delta = ((part / whole - previousPart / previousWhole) * 100).toFixed(1);
  return `比上周 ${Number(delta) > 0 ? "+" : ""}${delta} 个百分点`;
}

/**
 * 资料有效期（#124、#125）：有知识库权限时显示即将到期和已失效的文档数，列出最早到期的几份，周报里一起打印。
 * 都没有时不占位置。
 */
function ExpirySection() {
  const canView = useCan(PERMISSIONS.KB_VIEW);
  const summary = useQuery({
    queryKey: ["knowledge-bases", "expiry-summary"],
    queryFn: fetchExpirySummary,
    enabled: canView,
  });
  if (!summary.data || summary.data.expiring + summary.data.expired === 0) return null;
  const { expiring, expired, warningDays, documents } = summary.data;
  const total = expiring + expired;
  return (
    <section
      aria-label="资料有效期"
      className="flex flex-col gap-2 rounded-lg border bg-card p-3 text-sm print:break-inside-avoid"
    >
      <div role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-medium">资料有效期</span>
        {expiring > 0 && (
          <span>
            {expiring} 份将在 {warningDays} 天内到期
          </span>
        )}
        {expired > 0 && <span className="text-destructive">{expired} 份已失效，不再参与回答</span>}
        <Link to="/admin/knowledge" className="ml-auto inline-flex items-center hover:underline print:hidden">
          去知识库处理
          <ChevronRight aria-hidden="true" className="size-4" />
        </Link>
      </div>
      <ul className="flex flex-col gap-1">
        {documents.slice(0, EXPIRY_ROWS).map((document) => (
          <li key={document.documentId} className="flex flex-wrap items-center gap-x-2">
            <Badge variant={document.validity === "EXPIRED" ? "destructive" : "default"}>
              {document.validity === "EXPIRED" ? "已失效" : "即将到期"}
            </Badge>
            <Link
              to={`/admin/knowledge/${document.knowledgeBaseId}?validity=${document.validity}`}
              className="wrap-anywhere hover:underline"
            >
              {document.filename}
            </Link>
            <span className="text-muted-foreground">
              {document.knowledgeBaseName} · {document.validity === "EXPIRED" ? "失效于" : "到期"} {document.expiresOn}
            </span>
          </li>
        ))}
      </ul>
      {total > EXPIRY_ROWS && (
        <p className="text-xs text-muted-foreground">还有 {total - EXPIRY_ROWS} 份，到知识库里查看。</p>
      )}
    </section>
  );
}

/** 每日提问、拒答、差评。条形长度按这段时间里单日最多的提问数换算。 */
function DailyTable({ daily }: { daily: UsageStats["daily"] }) {
  const max = Math.max(1, ...daily.map((day) => day.questions));
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-lg font-medium">每日趋势</h2>
      <div className="max-h-96 overflow-y-auto rounded-lg border bg-card print:max-h-none print:overflow-visible">
        <Table aria-label="每日趋势">
          <TableHeader className="sticky top-0 bg-card">
            <TableRow>
              <TableHead>日期</TableHead>
              <TableHead className="w-1/2">提问</TableHead>
              <TableHead className="text-right">拒答</TableHead>
              <TableHead className="text-right">差评</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {daily.map((day) => (
              <TableRow key={day.date}>
                <TableCell className="tabular-nums">{day.date.slice(5)}</TableCell>
                <TableCell>
                  <div className="flex items-center gap-2">
                    <div className="h-2 flex-1 overflow-hidden rounded-sm bg-secondary">
                      <div className="h-full bg-primary" style={{ width: `${(day.questions / max) * 100}%` }} />
                    </div>
                    <span className="w-8 text-right tabular-nums">{day.questions}</span>
                  </div>
                </TableCell>
                <TableCell className="text-right tabular-nums">{day.refused}</TableCell>
                <TableCell className="text-right tabular-nums">{day.disliked}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </section>
  );
}

function Ranking({ title, rows, to }: { title: string; rows: { label: string; count: number }[]; to?: string }) {
  return (
    <section className="flex flex-col gap-2 print:break-inside-avoid">
      <h2 className="flex items-center text-lg font-medium">
        {title}
        {to && (
          <Link to={to} className="ml-auto inline-flex items-center text-sm font-normal hover:underline print:hidden">
            查看全部
            <ChevronRight aria-hidden="true" className="size-4" />
          </Link>
        )}
      </h2>
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">没有。</p>
      ) : (
        <ol className="flex flex-col divide-y rounded-lg border bg-card text-sm">
          {rows.map((row) => (
            <li key={row.label} className="flex items-start gap-3 px-3 py-2">
              <span className="min-w-0 flex-1 break-words">{row.label}</span>
              <span className="shrink-0 tabular-nums text-muted-foreground">{row.count} 次</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function percent(part: number, whole: number) {
  return whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "-";
}
