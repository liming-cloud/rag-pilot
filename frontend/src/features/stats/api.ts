import { ApiError, json, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

export type Totals = {
  questions: number;
  askers: number;
  refused: number;
  /** 点了赞或踩的回答数。 */
  rated: number;
  liked: number;
  disliked: number;
  /** 差评和答不上来、还没标记已处理的记录数。 */
  openReviews: number;
  /** 现在能使用问答的在职员工数（启用的、有“使用问答”权限的账号），算提问人数占比用；没有账号历史，上一周对比也用当前人数（#145 A11）。 */
  activeStaff: number;
  /** 短时间重复提问：同一员工 10 分钟内又问了意思相近的问题（问题向量相似度达到归类门槛），说明上一次没得到想要的答案（#145 A11）。 */
  repeatQuestions: number;
};

/** 与后端 answering 模块的 UsageStats 一致。日期按部署配置的统计时区划分（D-045）。 */
export type UsageStats = {
  from: string;
  to: string;
  /** 统计时区，如 Asia/Shanghai。 */
  zone: string;
  totals: Totals;
  /**
   * 按周查看时是上一周的汇总，用来对比；本周还没过完时只统计到今天，上一周取同样的几天。按最近 N 天查看时为 null（#125）。
   */
  previous: Totals | null;
  daily: { date: string; questions: number; refused: number; disliked: number }[];
  refusalReasons: { reason: string; count: number }[];
  /** 待处理次数最多的知识缺口（按意思归类，D-047），代表问题和次数。 */
  topGaps: { question: string; count: number }[];
  topCitedDocuments: { documentName: string; count: number }[];
};

export const RANGES = [7, 30, 90] as const;
export type Range = (typeof RANGES)[number];

/** 最近 N 天，或某个自然周（周一的日期，yyyy-MM-dd）。 */
export type StatsPeriod = { days: Range } | { week: string };

export async function fetchUsageStats(period: StatsPeriod): Promise<UsageStats> {
  return json(await authFetch(`/api/answers/stats${query(period)}`), "读取运营概览失败");
}

/**
 * 导出这段时间的问答明细（stats:export）：每条问答一行，UTF-8 带 BOM 的 CSV，Excel 直接打开中文不乱码。
 * 文件名由后端给出。
 */
export async function exportUsageCsv(period: StatsPeriod): Promise<{ filename: string; blob: Blob }> {
  const response = await authFetch(`/api/answers/stats/export${query(period)}`);
  if (!response.ok) {
    await json(response, "导出失败");
    throw new ApiError(response.status, "导出失败");
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/.exec(disposition)?.[1];
  return { filename: encoded ? decodeURIComponent(encoded) : "运营明细.csv", blob: await response.blob() };
}

/** 一个自然周：周一到周日。 */
export type Week = { start: string; end: string };

/**
 * 统计时区里最近 {@code count} 个自然周（周一到周日），本周在前。只用来生成选项，统计的切分以后端为准。
 */
export function recentWeeks(zone: string, count = 12, now = new Date()): Week[] {
  const today = new Intl.DateTimeFormat("sv-SE", { timeZone: zone }).format(now);
  const date = new Date(`${today}T00:00:00Z`);
  const monday = new Date(date.getTime() - ((date.getUTCDay() + 6) % 7) * 86_400_000);
  return Array.from({ length: count }, (_, index) => {
    const start = new Date(monday.getTime() - index * 7 * 86_400_000);
    const end = new Date(start.getTime() + 6 * 86_400_000);
    return { start: start.toISOString().slice(0, 10), end: end.toISOString().slice(0, 10) };
  });
}

/** 常见时区给个好懂的名字，其余直接显示时区名。 */
export function zoneLabel(zone: string): string {
  return (
    (
      { "Asia/Shanghai": "北京时间", "Asia/Hong_Kong": "香港时间", "Asia/Singapore": "新加坡时间" } as Record<
        string,
        string
      >
    )[zone] ?? zone
  );
}

/** 运营概览的查询键。 */
export const statsKeys = {
  usage: (period: StatsPeriod) => ["usage-stats", period] as const,
} as const;
