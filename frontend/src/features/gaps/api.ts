import type { Answer } from "@/features/chat/api";
import { json, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 一类缺口里的一条原始记录：拒答或差评。 */
export type GapRecord = {
  id: number;
  question: string;
  askerName: string;
  createdAt: string;
  refused: boolean;
  /** 差评时员工的留言。 */
  comment: string | null;
  /** 当时实际检索的知识库；记下检索范围之前的旧记录为空。 */
  knowledgeBases: string[];
  /** 当时引用的文档名；拒答没有引用。 */
  citedDocuments: string[];
  reviewStatus: "OPEN" | "RESOLVED";
};

/**
 * 一类知识缺口（#125、D-047）：意思相近的拒答和差评归在一起。代表问题取类里出现最多的原文；
 * 重新回答用代表问题对应的那条记录。
 */
export type GapGroup = {
  /** 代表记录的 id，也用作这一类的标识。 */
  id: number;
  question: string;
  /** 范围内的记录数（含已处理）。 */
  count: number;
  /** 还没处理的记录数；为 0 时整类算已处理。 */
  openCount: number;
  refusedCount: number;
  dislikedCount: number;
  /** 不同的问法数（按原文去重）。 */
  phrasings: number;
  lastAskedAt: string;
  /** 类里的记录当时检索过的知识库，出现多的在前，提示资料该补到哪个库（D-047）。 */
  knowledgeBases: string[];
  /** 类里差评记录当时引用的文档（拒答没有引用），提示该改哪份资料。 */
  citedDocuments: string[];
  records: GapRecord[];
};

export type GapReport = {
  from: string;
  to: string;
  zone: string;
  /** SEMANTIC：按向量相似度归类；TEXT：嵌入服务不可用时退回按原文分组。 */
  grouping: "SEMANTIC" | "TEXT";
  /** 范围内参与归类的记录数。 */
  records: number;
  /** 超过 1000 条时只取最近的 1000 条归类。 */
  truncated: boolean;
  groups: GapGroup[];
};

export const GAP_RANGES = [7, 30, 90] as const;
export type GapRange = (typeof GAP_RANGES)[number];

/** 按时间范围现算的知识缺口，按待处理数、总数从多到少（answer:view）。 */
export async function fetchGaps(filter: { days: GapRange; status: "open" | "all" }): Promise<GapReport> {
  return json(await authFetch(`/api/answers/gaps${query(filter)}`), "读取知识缺口失败");
}

/** 整类标记已处理（answer:review）：类里还没处理的记录都标记已处理，附同一条说明。 */
export async function resolveGap(recordIds: number[], note?: string): Promise<void> {
  const response = await authFetch("/api/answers/gaps/resolve", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ recordIds, note }),
  });
  if (!response.ok) await json(response, "标记已处理失败");
}

/** 用当前知识库重新回答代表问题，结果不写入回答记录（answer:recheck，D-017）。 */
export async function recheckGap(recordId: number): Promise<Answer> {
  return json(await authFetch(`/api/answers/${recordId}/recheck`, { method: "POST" }), "重新回答失败");
}
