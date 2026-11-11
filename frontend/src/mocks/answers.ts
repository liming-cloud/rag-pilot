import { http, HttpResponse } from "msw";
import type { AnswerReview } from "@/features/answers/api";
import type { Answer } from "@/features/chat/api";
import { matches, paginate, problem } from "./page";

/** 回答记录与处理的假接口（D-039）。 */
const sampleReviews: AnswerReview[] = [
  {
    id: 601,
    userId: 2,
    askerName: "店员小王",
    question: "临期商品打几折？",
    answerText: "保质期剩余不足三分之一的商品按原价七折销售 [1]。",
    refused: false,
    refusalReason: null,
    topRelevance: 0.93,
    rating: "DOWN",
    comment: "门店规定是五折",
    createdAt: new Date(Date.now() - 3 * 3_600_000).toISOString(),
    citedDocuments: ["临期与报损管理.md"],
    rewrittenQuestion: null,
    reviewStatus: "OPEN",
    resolutionNote: null,
    resolvedAt: null,
  },
  {
    id: 602,
    userId: 3,
    askerName: "店员小李",
    question: "门店几点打烊？",
    answerText: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
    refused: true,
    refusalReason: "LOW_RELEVANCE",
    topRelevance: 0.02,
    rating: null,
    comment: null,
    createdAt: new Date(Date.now() - 4 * 3_600_000).toISOString(),
    citedDocuments: [],
    rewrittenQuestion: "门店的营业结束时间是几点？",
    reviewStatus: "OPEN",
    resolutionNote: null,
    resolvedAt: null,
  },
];
let reviews: AnswerReview[] = [];

export function resetAnswers() {
  reviews = [
    ...sampleReviews.map((review) => ({ ...review })),
    // 多生成一些记录（时间相对现在），原型里能看到分页和筛选。
    ...Array.from({ length: 40 }, (_, index): AnswerReview => ({
      id: 700 + index,
      userId: 100 + (index % 10),
      askerName: `门店店员 ${(index % 10) + 1}`,
      question: `第 ${index + 1} 个员工提问：${["临期商品怎么处理", "交班差额怎么写说明", "会员积分怎么抵扣", "停电怎么办"][index % 4]}？`,
      answerText: index % 3 === 0 ? "知识库里没有找到能回答这个问题的可靠依据。" : "按规定处理 [1]。",
      refused: index % 3 === 0,
      refusalReason: index % 3 === 0 ? "LOW_RELEVANCE" : null,
      topRelevance: index % 3 === 0 ? 0.05 : 0.9,
      rating: index % 3 === 0 ? null : index % 2 === 0 ? "DOWN" : "UP",
      comment: index % 6 === 2 ? "和门店实际做法不一样" : null,
      createdAt: new Date(Date.now() - (index + 1) * 36 * 3_600_000).toISOString(),
      citedDocuments: index % 3 === 0 ? [] : ["临期与报损管理.md"],
      rewrittenQuestion: null,
      reviewStatus: index % 5 === 0 ? "RESOLVED" : "OPEN",
      resolutionNote: index % 5 === 0 ? "已补充文档" : null,
      resolvedAt: null,
    })),
  ];
}

export const sampleAnswer: Answer = {
  id: 501,
  text: "保质期剩余三分之一时贴黄色临期标签并移到临期专区 [1]；剩余 1 天的商品当天闭店前下架，登记报损后由店长确认 [2]。",
  refused: false,
  refusalReason: null,
  citations: [
    {
      index: 1,
      chunkId: 101,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "临期商品 > 下架时限",
      excerpt: "保质期剩余不足三分之一的商品，贴黄色临期标签，集中陈列到临期专区，按原价七折销售。",
      documentUpdatedAt: "2026-10-26T09:00:00+08:00",
      documentState: "CURRENT",
    },
    {
      index: 2,
      chunkId: 102,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "报损 > 登记与确认",
      excerpt: "保质期仅剩 1 天的商品，当天闭店前全部下架，在报损登记表写明品名、数量和原因，店长签字确认。",
      documentUpdatedAt: "2026-10-26T09:00:00+08:00",
      documentState: "CURRENT",
    },
  ],
  dataCitations: [],
  topRelevance: 0.97,
};

export const answerHandlers = [
  http.get("/api/answers", ({ request }) => {
    const url = new URL(request.url);
    const filter = url.searchParams.get("filter");
    const status = url.searchParams.get("status") ?? "open";
    const days = Number(url.searchParams.get("days")) || null;
    const keyword = url.searchParams.get("keyword");
    const since = days ? Date.now() - days * 86_400_000 : null;
    return HttpResponse.json(
      paginate(
        reviews
          .filter((review) =>
            status === "all"
              ? true
              : status === "resolved"
                ? review.reviewStatus === "RESOLVED"
                : review.reviewStatus === "OPEN",
          )
          .filter((review) =>
            filter === "refused" ? review.refused : filter === "all" ? true : review.rating === "DOWN",
          )
          .filter((review) => since === null || new Date(review.createdAt).getTime() >= since)
          .filter((review) => matches(keyword, review.question))
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
        url,
      ),
    );
  }),
  http.patch("/api/answers/:id/review", async ({ params, request }) => {
    const { status, note } = (await request.json()) as { status: "OPEN" | "RESOLVED"; note?: string };
    const review = reviews.find((candidate) => candidate.id === Number(params.id));
    if (!review) {
      return problem(404, "ANSWERING_NOT_FOUND", "回答不存在");
    }
    Object.assign(review, {
      reviewStatus: status,
      resolutionNote: status === "RESOLVED" ? note?.trim() || null : null,
      resolvedAt: status === "RESOLVED" ? new Date().toISOString() : null,
    });
    return new HttpResponse(null, { status: 204 });
  }),
  http.post("/api/answers/:id/recheck", () =>
    HttpResponse.json({
      ...sampleAnswer,
      id: null,
      text: "保质期剩余不足三分之一的商品按原价五折销售 [1]。",
      rewrite: null,
      timings: null,
    }),
  ),
  // 原型（#145 A11）：员工点开了第几个引用，接接口后由后端记下。
  http.post("/api/answers/:id/citations/:index/opened", () => new HttpResponse(null, { status: 204 })),
  http.put("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  http.delete("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
];
