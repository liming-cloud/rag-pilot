import { http, HttpResponse } from "msw";
import type { UsageStats } from "@/features/stats/api";
import { topGaps } from "./gaps";
import { forbidden } from "./identity";

/** 某一天的确定示例数据：工作日问得多，周末少；同一天每次生成都一样，按周和按天数查看对得上。 */
function sampleDay(date: string) {
  const index = Math.round(new Date(`${date}T00:00:00Z`).getTime() / 86_400_000);
  const weekend = [0, 6].includes(new Date(`${date}T00:00:00Z`).getUTCDay());
  const questions = (weekend ? 6 : 18) + ((index * 7) % 11);
  return {
    date,
    questions,
    refused: Math.round(questions * 0.12) + (index % 3 === 0 ? 1 : 0),
    disliked: index % 4 === 0 ? 1 : 0,
  };
}

function shift(date: string, days: number) {
  return new Date(new Date(`${date}T00:00:00Z`).getTime() + days * 86_400_000).toISOString().slice(0, 10);
}

/** 统计时区（北京时间）的今天。 */
function statsToday() {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date());
}

function totalsOf(daily: ReturnType<typeof sampleDay>[]) {
  const sum = (key: "questions" | "refused" | "disliked") => daily.reduce((total, day) => total + day[key], 0);
  const questions = sum("questions");
  const refused = sum("refused");
  const disliked = sum("disliked");
  return {
    questions,
    askers: Math.min(23, 6 + daily.length),
    refused,
    rated: Math.round((questions - refused) * 0.3),
    liked: Math.round((questions - refused) * 0.3) - disliked,
    disliked,
    openReviews: Math.round(refused * 0.4) + disliked,
    // 能用问答的在职员工数取当前人数；短时间重复提问见 README 的产品指标（#145 A11）。
    activeStaff: 36,
    repeatQuestions: Math.round(questions * 0.08),
    citedAnswers: questions - refused,
    openedAnswers: Math.round((questions - refused) * 0.35),
  };
}

/** 从 from 起 days 天的示例统计；按周查看时带上一周的汇总用来对比。 */
function sampleStats(from: string, days: number, withPrevious: boolean): UsageStats {
  const daily = Array.from({ length: days }, (_, index) => sampleDay(shift(from, index)));
  const totals = totalsOf(daily);
  const { refused } = totals;
  return {
    from: daily[0]!.date,
    to: daily.at(-1)!.date,
    zone: "Asia/Shanghai",
    totals,
    previous: withPrevious
      ? totalsOf(Array.from({ length: days }, (_, index) => sampleDay(shift(from, index - days))))
      : null,
    daily,
    refusalReasons: [
      { reason: "LOW_RELEVANCE", count: Math.round(refused * 0.7) },
      { reason: "NO_EVIDENCE", count: Math.round(refused * 0.2) },
      { reason: "MODEL_DECLINED", count: refused - Math.round(refused * 0.7) - Math.round(refused * 0.2) },
    ],
    topGaps: topGaps(days, 5),
    topCitedDocuments: [
      { documentName: "临期与报损管理.md", count: 64 },
      { documentName: "商品陈列与补货规范.md", count: 41 },
      { documentName: "收银与交接班.docx", count: 33 },
      { documentName: "会员与促销.md", count: 18 },
    ],
  };
}

function statsFor(url: URL) {
  const week = url.searchParams.get("week");
  // 本周还没过完：统计到今天为止，与上周同样的几天对比。
  if (week) {
    const elapsed = Math.round((Date.parse(statsToday()) - Date.parse(week)) / 86_400_000) + 1;
    return sampleStats(week, Math.min(7, Math.max(1, elapsed)), true);
  }
  const days = Number(url.searchParams.get("days")) || 30;
  return sampleStats(shift(statsToday(), 1 - days), days, false);
}

/** 问答明细 CSV（每条一行），带 BOM，与后端一致。 */
function statsCsv(stats: UsageStats) {
  const header = "时间,提问人,问题,结果,评价,留言,引用文档,处理状态";
  const rows = stats.daily.flatMap((day) =>
    Array.from({ length: Math.min(day.questions, 3) }, (_, index) =>
      [
        `${day.date} ${String(9 + index * 3).padStart(2, "0")}:15`,
        `门店店员 ${index + 1}`,
        `"示例问题，含逗号, 和""引号"""`,
        index === 0 && day.refused > 0 ? "答不上来" : "已回答",
        index === 1 && day.disliked > 0 ? "没帮助" : "",
        "",
        index === 0 && day.refused > 0 ? "" : "临期与报损管理.md",
        index === 0 && day.refused > 0 ? "待处理" : "",
      ].join(","),
    ),
  );
  return `\uFEFF${[header, ...rows].join("\r\n")}\r\n`;
}

export const statsHandlers = [
  http.get("/api/answers/stats", ({ request }) => HttpResponse.json(statsFor(new URL(request.url)))),
  http.get("/api/answers/stats/export", ({ request }) => {
    const denied = forbidden("stats:export");
    if (denied) return denied;
    const stats = statsFor(new URL(request.url));
    const filename = `运营明细_${stats.from}_${stats.to}.csv`;
    return new HttpResponse(statsCsv(stats), {
      headers: {
        "Content-Type": "text/csv; charset=utf-8",
        "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`,
      },
    });
  }),
];
