import { useMutation, useQuery } from "@tanstack/react-query";
import { ChevronRight, Download, Printer } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS, useCan } from "@/lib/permissions";
import { ExpirySection } from "./ExpirySection";
import { DailyTable, Ranking, percent } from "./StatsTables";
import {
  exportUsageCsv,
  fetchUsageStats,
  RANGES,
  recentWeeks,
  statsKeys,
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
  DATA_DENIED: "无权查看要查的数据",
  DATA_UNAVAILABLE: "数据暂时查不到",
};

/**
 * 运营概览（管理端首页）：员工用得怎么样、哪些资料该补、哪些资料该更新，供试点汇报和补文档参考。
 * 可以看最近 N 天，也可以看某个自然周并与上一周对比（#125、D-047）；“打印”用浏览器存成 PDF 作为周报，
 * “导出明细”下载这段时间每条问答的 CSV（stats:export）。打印时隐藏菜单和按钮。
 * 有回答记录权限时，“待处理”链到待处理的回答记录，知识缺口链到知识缺口页。
 * 比例在前端算：拒答率的分母是提问数，评价率和差评率的分母是给出了回答的题数。
 */
export function StatsPage() {
  const [period, setPeriod] = useState<StatsPeriod>({ days: 30 });
  const stats = useQuery({ queryKey: statsKeys.usage(period), queryFn: () => fetchUsageStats(period) });
  const weeks = recentWeeks(stats.data?.zone ?? "Asia/Shanghai");
  const week = "week" in period ? period.week : "";
  const exporting = useMutation({
    mutationFn: () => exportUsageCsv(period),
    onSuccess: ({ filename, blob }) => download(filename, blob),
  });

  return (
    <main className="mx-auto flex w-full max-w-[1600px] flex-col gap-6 p-6 md:p-8 xl:px-10 print:max-w-none print:p-0">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto font-display text-2xl font-semibold tracking-tight">{week ? "运营周报" : "运营概览"}</h1>
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
      <ErrorMessage error={stats.error} className="text-base" />
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
    // 产品指标（#145 A11，口径见 README）：用的人多不多、用得深不深、有没有问了又问。
    {
      label: "提问人数占比",
      value: percent(totals.askers, totals.activeStaff),
      note: `${totals.activeStaff} 位能用问答的员工里 ${totals.askers} 位问过`,
      change: previous ? rateChange(totals, previous, (t) => [t.askers, t.activeStaff]) : undefined,
    },
    {
      label: "人均提问",
      value: totals.askers === 0 ? "-" : (totals.questions / totals.askers).toFixed(1),
      note: "问过的员工平均每人提问数",
    },
    {
      label: "短时间重复提问",
      value: percent(totals.repeatQuestions, totals.questions),
      note: `${totals.repeatQuestions} 题是 10 分钟内又问了相近的问题`,
      change: previous ? rateChange(totals, previous, (t) => [t.repeatQuestions, t.questions]) : undefined,
    },
    {
      label: "引用点开率",
      value: percent(totals.openedAnswers, totals.citedAnswers),
      note: `${totals.citedAnswers} 条带引用的回答里 ${totals.openedAnswers} 条被点开过引用`,
      change: previous ? rateChange(totals, previous, (t) => [t.openedAnswers, t.citedAnswers]) : undefined,
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
          <div className="grid items-start gap-6 xl:grid-cols-2">
          <DailyTable daily={stats.daily} />
          <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-1 print:grid-cols-2">
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
      <span className="text-gradient text-3xl font-semibold tracking-tight">{card.value}</span>
      <span className="text-xs text-muted-foreground">{card.note}</span>
      {card.change && <span className="text-xs">{card.change}</span>}
    </>
  );
  return (
    <li className="flex rounded-xl border bg-card shadow-sm print:break-inside-avoid">
      {card.to ? (
        <Link
          to={card.to}
          aria-label={`${card.label} ${card.value}，去处理`}
          className="flex flex-1 flex-col gap-1 rounded-lg p-4 hover:bg-accent/50"
        >
          {body}
        </Link>
      ) : (
        <div className="flex flex-1 flex-col gap-1 p-4">{body}</div>
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
