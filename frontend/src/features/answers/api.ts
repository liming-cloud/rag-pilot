import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 与后端 answering 模块的 AnswerReview 一致。 */
export type AnswerReview = {
  id: number;
  userId: number;
  question: string;
  answerText: string;
  refused: boolean;
  refusalReason: string | null;
  topRelevance: number | null;
  rating: "UP" | "DOWN" | null;
  comment: string | null;
  createdAt: string;
  citedDocuments: string[];
};

export type ReviewFilter = "disliked" | "refused" | "all";

/** 管理员查看回答记录，按时间从新到旧。 */
export async function fetchAnswerReviews(filter: ReviewFilter): Promise<AnswerReview[]> {
  return json(await authFetch(`/api/answers?filter=${filter}&limit=100`), "读取回答记录失败");
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
      review.comment ? `员工留言：${review.comment}` : "",
    ]
      .filter(Boolean)
      .join(" "),
  }));
}
