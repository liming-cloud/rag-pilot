import type { Answer } from "@/features/chat/api";
import { json, type Page, query } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 与后端 answering 模块的 AnswerReview 一致。 */
export type AnswerReview = {
  id: number;
  userId: number;
  /** 提问人的显示名，由后端直接给出，不用为了显示名字再读全部账号。 */
  askerName: string;
  question: string;
  answerText: string;
  refused: boolean;
  refusalReason: string | null;
  topRelevance: number | null;
  rating: "UP" | "DOWN" | null;
  comment: string | null;
  createdAt: string;
  citedDocuments: string[];
  /** 低分时系统改写后的问题。 */
  rewrittenQuestion: string | null;
  reviewStatus: "OPEN" | "RESOLVED";
  resolutionNote: string | null;
  resolvedAt: string | null;
};

export type ReviewFilter = "disliked" | "refused" | "all";

/** 回答记录的筛选（D-039）：类型、处理状态、最近几天、问题关键词。 */
export type AnswerFilter = {
  filter: ReviewFilter;
  status: "open" | "resolved" | "all";
  days?: 7 | 30 | 90 | null;
  keyword?: string;
  page?: number;
  size?: number;
};

/** 管理员按筛选条件分页查看回答记录，按时间从新到旧。 */
export async function fetchAnswerReviews(filter: AnswerFilter): Promise<Page<AnswerReview>> {
  return json(await authFetch(`/api/answers${query(filter)}`), "读取回答记录失败");
}

/** 导出评测题草稿时取当前筛选条件下的全部记录，最多 1000 条。 */
export async function fetchAllAnswerReviews(filter: AnswerFilter): Promise<AnswerReview[]> {
  const all: AnswerReview[] = [];
  for (let page = 1; all.length < 1000; page++) {
    const result = await fetchAnswerReviews({ ...filter, page, size: 100 });
    all.push(...result.items);
    if (result.items.length === 0 || all.length >= result.total) break;
  }
  return all.slice(0, 1000);
}

/** 标记已处理（附说明）或重新打开。 */
export async function markReview(id: number, status: "RESOLVED" | "OPEN", note?: string): Promise<void> {
  const response = await authFetch(`/api/answers/${id}/review`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status, note }),
  });
  if (!response.ok) {
    await json(response, "保存处理状态失败");
  }
}

/** 用当前知识库重新回答记录里的问题，结果不写入回答记录。 */
export async function recheckAnswer(id: number): Promise<Answer> {
  return json(await authFetch(`/api/answers/${id}/recheck`, { method: "POST" }), "重新回答失败");
}

/**
 * 把回答记录转成评测题草稿（格式同 backend/rag-app/src/test/resources/eval/cases.json）。
 * 预期文档先填当时引用的第一个文档，答案关键词留空：都要人工确认后再放进仓库外的私有评测集。
 */
export function toEvalDrafts(reviews: AnswerReview[]) {
  return reviews.map((review) => ({
    id: `真实-${review.id}`,
    question: review.question,
    kind: review.refused ? "unanswerable" : "answerable",
    expectedDocument: review.citedDocuments[0] ?? null,
    keywords: [] as string[],
    style: "真实",
    note: [
      "待标注：确认 kind、expectedDocument，并填写答案关键词。",
      review.refused ? "当时没答上来；如果知识库里本该有答案，改成 answerable。" : `当时的回答：${review.answerText}`,
      review.rewrittenQuestion ? `系统改写为：${review.rewrittenQuestion}` : "",
      review.comment ? `员工留言：${review.comment}` : "",
      review.resolutionNote ? `处理说明：${review.resolutionNote}` : "",
    ]
      .filter(Boolean)
      .join(" "),
  }));
}
