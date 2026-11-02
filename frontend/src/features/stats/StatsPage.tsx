import { useQuery } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchExpirySummary } from "@/features/knowledge/api";
import { useCan } from "@/lib/permissions";
import { fetchUsageStats, RANGES, type Range, type UsageStats, zoneLabel } from "./api";

const REASONS: Record<string, string> = {
  NO_EVIDENCE: "知识库里没有内容",
  LOW_RELEVANCE: "相关度低于门槛",
  MODEL_DECLINED: "模型判断资料不足",
  UNCITED: "回答没有可核对的引用",
};

/**
 * 运营概览（管理端首页）：试点期间员工用得怎么样、答不上来的是什么、哪些文档最常被引用，供试点汇报和补文档参考；
 * 有回答记录权限时，“待处理”直接链到待处理的差评和答不上来的问题。
 * 比例在前端算：拒答率的分母是提问数，评价率和差评率的分母是给出了回答的题数。
 */
export function StatsPage() {
  const [days, setDays] = useState<Range>(30);
  const stats = useQuery({ queryKey: ["usage-stats", days], queryFn: () => fetchUsageStats(days) });

  return (
    <main className="mx-auto flex max-w-4xl flex-col gap-6 p-6 md:p-8">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-auto font-serif text-2xl font-semibold">运营概览</h1>
        {RANGES.map((range) => (
          <Button
            key={range}
            size="sm"
            variant={days === range ? "default" : "outline"}
            aria-pressed={days === range}
            onClick={() => setDays(range)}
          >
            近 {range} 天
          </Button>
        ))}
      </div>
      <ExpiryNotice />
      {stats.isPending && <p className="text-muted-foreground">正在统计…</p>}
      {stats.isError && <p className="text-destructive">{stats.error.message}</p>}
      {stats.data && <StatsView stats={stats.data} />}
    </main>
  );
}

function StatsView({ stats }: { stats: UsageStats }) {
  const { totals } = stats;
  const canReview = useCan("answer:view");
  const answered = totals.questions - totals.refused;
  const cards = [
    { label: "提问", value: String(totals.questions), note: `${totals.askers} 位员工` },
    { label: "拒答率", value: percent(totals.refused, totals.questions), note: `${totals.refused} 题答不上来` },
    { label: "评价率", value: percent(totals.rated, answered), note: `已回答 ${answered} 题中有评价的` },
    {
      label: "差评率",
      value: percent(totals.disliked, answered),
      note: `${totals.disliked} 个差评，${totals.liked} 个好评`,
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
      </p>
      <ul aria-label="汇总" className="grid grid-cols-2 gap-3 md:grid-cols-5">
        {cards.map((card) => {
          const body = (
            <>
              <span className="flex items-center text-sm text-muted-foreground">
                {card.label}
                {"to" in card && card.to && <ChevronRight aria-hidden="true" className="ml-auto size-4" />}
              </span>
              <span className="text-2xl font-semibold">{card.value}</span>
              <span className="text-xs text-muted-foreground">{card.note}</span>
            </>
          );
          return (
            <li key={card.label} className="flex rounded-lg border bg-card">
              {"to" in card && card.to ? (
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
        })}
      </ul>
      {totals.questions === 0 ? (
        <p className="text-muted-foreground">这段时间还没有提问。</p>
      ) : (
        <>
          <DailyTable daily={stats.daily} />
          <div className="grid gap-6 md:grid-cols-2">
            <Ranking
              title="拒答原因"
              rows={stats.refusalReasons.map((row) => ({ label: REASONS[row.reason] ?? row.reason, count: row.count }))}
            />
            <Ranking
              title="答不上来次数最多的问题"
              rows={stats.topRefusedQuestions.map((row) => ({ label: row.question, count: row.count }))}
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

/**
 * 资料到期提醒（#124）：有知识库权限时显示即将到期和已失效的文档数，点过去是按有效期筛选的知识库列表。
 * 都没有时不占位置。
 */
function ExpiryNotice() {
  const canView = useCan("kb:view");
  const summary = useQuery({
    queryKey: ["knowledge-bases", "expiry-summary"],
    queryFn: fetchExpirySummary,
    enabled: canView,
  });
  if (!summary.data || summary.data.expiring + summary.data.expired === 0) return null;
  const { expiring, expired, warningDays } = summary.data;
  return (
    <p role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border bg-card p-3 text-sm">
      <span className="font-medium">资料有效期</span>
      {expiring > 0 && (
        <span>
          {expiring} 份将在 {warningDays} 天内到期
        </span>
      )}
      {expired > 0 && <span className="text-destructive">{expired} 份已失效，不再参与回答</span>}
      <Link to="/admin/knowledge" className="ml-auto inline-flex items-center hover:underline">
        去知识库处理
        <ChevronRight aria-hidden="true" className="size-4" />
      </Link>
    </p>
  );
}

/** 每日提问、拒答、差评。条形长度按这段时间里单日最多的提问数换算。 */
function DailyTable({ daily }: { daily: UsageStats["daily"] }) {
  const max = Math.max(1, ...daily.map((day) => day.questions));
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-lg font-medium">每日趋势</h2>
      <div className="max-h-96 overflow-y-auto rounded-lg border bg-card">
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

function Ranking({ title, rows }: { title: string; rows: { label: string; count: number }[] }) {
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-lg font-medium">{title}</h2>
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
