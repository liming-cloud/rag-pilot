import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 与后端 answering 模块的 UsageStats 一致。日期按部署配置的统计时区划分（D-045）。 */
export type UsageStats = {
  from: string;
  to: string;
  /** 统计时区，如 Asia/Shanghai。 */
  zone: string;
  totals: {
    questions: number;
    askers: number;
    refused: number;
    /** 点了赞或踩的回答数。 */
    rated: number;
    liked: number;
    disliked: number;
    /** 差评和答不上来、还没标记已处理的记录数。 */
    openReviews: number;
  };
  daily: { date: string; questions: number; refused: number; disliked: number }[];
  refusalReasons: { reason: string; count: number }[];
  topRefusedQuestions: { question: string; count: number }[];
  topCitedDocuments: { documentName: string; count: number }[];
};

export const RANGES = [7, 30, 90] as const;
export type Range = (typeof RANGES)[number];

export async function fetchUsageStats(days: Range): Promise<UsageStats> {
  return json(await authFetch(`/api/answers/stats?days=${days}`), "读取使用统计失败");
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
