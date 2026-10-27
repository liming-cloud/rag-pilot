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
