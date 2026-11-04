import { ChevronRight } from "lucide-react";
import { Link } from "react-router";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { type UsageStats } from "./api";

/** 每日提问、拒答、差评。条形长度按这段时间里单日最多的提问数换算。 */
export function DailyTable({ daily }: { daily: UsageStats["daily"] }) {
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

export function Ranking({ title, rows, to }: { title: string; rows: { label: string; count: number }[]; to?: string }) {
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

export function percent(part: number, whole: number) {
  return whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "-";
}
