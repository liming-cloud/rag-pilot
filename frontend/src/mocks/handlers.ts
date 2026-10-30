import { http, HttpResponse } from "msw";
import type { AnswerReview } from "@/features/answers/api";
import type { Answer } from "@/features/chat/api";
import type { KnowledgeBase, KnowledgeDocument } from "@/features/knowledge/api";
import type { UsageStats } from "@/features/stats/api";
import { conversationHandlers, resetConversations } from "./conversations";
import { accountName, currentIdentity, identityHandlers, resetIdentity } from "./identity";
import { matches, paginate } from "./page";

/** 假接口与后端契约保持一致；示例数据只用于原型和测试。 */
export const sampleKnowledgeBases: KnowledgeBase[] = [
  { id: 1, name: "门店运营知识库" },
  { id: 2, name: "商品知识库" },
  // 多生成一些知识库，原型里能看到分页和筛选；只有管理员是成员。
  ...Array.from({ length: 22 }, (_, index) => ({ id: 3 + index, name: `区域 ${index + 1} 门店规范` })),
];

const sampleDocuments: Record<number, KnowledgeDocument[]> = {
  1: [
    {
      id: 11,
      filename: "临期与报损管理.md",
      status: "READY",
      error: null,
      chunkCount: 12,
      sizeBytes: 18_432,
      createdAt: "2026-10-26T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
    },
    {
      id: 12,
      filename: "旧版陈列手册.txt",
      status: "FAILED",
      error: "文件不是 UTF-8 编码，请用“另存为 UTF-8”后重新上传",
      chunkCount: 0,
      sizeBytes: 40_960,
      createdAt: "2026-10-26T09:05:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
    },
    // 多生成一些文档，原型里能看到分页。
    ...Array.from({ length: 30 }, (_, index): KnowledgeDocument => ({
      id: 300 + index,
      filename: `门店操作手册-第${index + 1}章.pdf`,
      status: "READY",
      error: null,
      chunkCount: 8 + (index % 7),
      sizeBytes: 120_000 + index * 9_000,
      createdAt: "2026-10-27T10:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
    })),
  ],
};

/** 假接口的可变数据；测试之间用 resetMockData 还原。 */
let knowledgeBases: KnowledgeBase[] = [];
let documents: Record<number, KnowledgeDocument[]> = {};
let members: Record<number, number[]> = {};
/** 知识库授权给了哪些角色（D-038）。 */
let roleGrants: Record<number, number[]> = {};
const pendingChunks = new Map<number, number>();
let nextId = 100;

export function resetMockData() {
  knowledgeBases = sampleKnowledgeBases.map((knowledgeBase) => ({ ...knowledgeBase }));
  documents = Object.fromEntries(Object.entries(sampleDocuments).map(([id, list]) => [id, [...list]]));
  members = Object.fromEntries(sampleKnowledgeBases.map((knowledgeBase) => [knowledgeBase.id, [1]]));
  members[1] = [1, 2];
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
  roleGrants = { 1: [4] };
  resetIdentity();
  resetConversations();
}

/**
 * 从 multipart 请求体里读出文件名和大小。不用 request.formData()：Node 24 在 jsdom 测试环境里解析时，
 * 会用 jsdom 的 File 构造对象而触发 undici 的断言。
 */
async function uploadedFile(request: Request) {
  const body = await request.text();
  const name = /filename="([^"]*)"/.exec(body)?.[1] ?? "";
  const content = body
    .split("\r\n\r\n")
    .slice(1)
    .join("\r\n\r\n")
    .replace(/\r\n--[^\r\n]*--\r\n$/, "");
  return { name, size: new TextEncoder().encode(content).length };
}

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
resetMockData();

// 与后端一致：RFC 9457 问题详情，带稳定的错误码 code（见 AGENTS.md、D-034）。
const problem = (status: number, code: string, detail: string) =>
  HttpResponse.json({ status, code, detail }, { status });

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
    },
    {
      index: 2,
      chunkId: 102,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "报损 > 登记与确认",
      excerpt: "保质期仅剩 1 天的商品，当天闭店前全部下架，在报损登记表写明品名、数量和原因，店长签字确认。",
    },
  ],
  topRelevance: 0.97,
};

/** 当前账号能不能访问这个知识库：直接授权，或所属角色被授权（D-038）。 */
function canAccess(knowledgeBaseId: number) {
  const { id, roleIds } = currentIdentity();
  return (
    (members[knowledgeBaseId]?.includes(id) ?? false) ||
    (roleGrants[knowledgeBaseId] ?? []).some((roleId) => roleIds.includes(roleId))
  );
}

/** 按天数生成确定的示例统计：工作日问得多，周末少。 */
function sampleStats(days: number): UsageStats {
  const to = new Date("2026-10-28T00:00:00+08:00");
  const daily = Array.from({ length: days }, (_, index) => {
    const date = new Date(to.getTime() - (days - 1 - index) * 86_400_000);
    const weekend = [0, 6].includes(date.getDay());
    const questions = (weekend ? 6 : 18) + ((index * 7) % 11);
    return {
      date: date.toISOString().slice(0, 10),
      questions,
      refused: Math.round(questions * 0.12) + (index % 3 === 0 ? 1 : 0),
      disliked: index % 4 === 0 ? 1 : 0,
    };
  });
  const sum = (key: "questions" | "refused" | "disliked") => daily.reduce((total, day) => total + day[key], 0);
  const questions = sum("questions");
  const refused = sum("refused");
  const disliked = sum("disliked");
  return {
    from: daily[0]!.date,
    to: daily.at(-1)!.date,
    totals: {
      questions,
      askers: Math.min(23, 8 + days),
      refused,
      rated: Math.round((questions - refused) * 0.3),
      liked: Math.round((questions - refused) * 0.3) - disliked,
      disliked,
      openReviews: Math.round(refused * 0.4) + disliked,
    },
    daily,
    refusalReasons: [
      { reason: "LOW_RELEVANCE", count: Math.round(refused * 0.7) },
      { reason: "NO_EVIDENCE", count: Math.round(refused * 0.2) },
      { reason: "MODEL_DECLINED", count: refused - Math.round(refused * 0.7) - Math.round(refused * 0.2) },
    ],
    topRefusedQuestions: [
      { question: "门店几点打烊？", count: 9 },
      { question: "员工餐补怎么报销？", count: 6 },
      { question: "冷柜坏了找谁修？", count: 4 },
      { question: "会员生日当天有什么优惠？", count: 3 },
    ],
    topCitedDocuments: [
      { documentName: "临期与报损管理.md", count: 64 },
      { documentName: "商品陈列与补货规范.md", count: 41 },
      { documentName: "收银与交接班.docx", count: 33 },
      { documentName: "会员与促销.md", count: 18 },
    ],
  };
}

export const handlers = [
  ...identityHandlers,
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
  http.get("/api/answers/stats", ({ request }) =>
    HttpResponse.json(sampleStats(Number(new URL(request.url).searchParams.get("days")) || 30)),
  ),
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
    HttpResponse.json({ ...sampleAnswer, id: null, text: "保质期剩余不足三分之一的商品按原价五折销售 [1]。" }),
  ),
  http.put("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  http.delete("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  // 与后端一致：只返回当前账号是成员的知识库，管理员也一样（D-017）。
  // 管理端列表：要放在 /:id 之前，免得 page 被当成 id。
  http.get("/api/knowledge-bases/page", ({ request }) => {
    const url = new URL(request.url);
    const keyword = url.searchParams.get("keyword");
    return HttpResponse.json(
      paginate(
        knowledgeBases
          .filter((knowledgeBase) => canAccess(knowledgeBase.id) && matches(keyword, knowledgeBase.name))
          .map((knowledgeBase) => ({
            ...knowledgeBase,
            documentCount: (documents[knowledgeBase.id] ?? []).length,
            failedCount: (documents[knowledgeBase.id] ?? []).filter((document) => document.status === "FAILED").length,
            roleGrantCount: (roleGrants[knowledgeBase.id] ?? []).length,
            memberCount: (members[knowledgeBase.id] ?? []).length,
          })),
        url,
      ),
    );
  }),
  http.get("/api/knowledge-bases/:id", ({ params }) => {
    const knowledgeBase = knowledgeBases.find((candidate) => candidate.id === Number(params.id));
    return knowledgeBase && canAccess(knowledgeBase.id)
      ? HttpResponse.json(knowledgeBase)
      : problem(404, "KNOWLEDGE_NOT_FOUND", "知识库不存在");
  }),
  http.get("/api/knowledge-bases", () =>
    HttpResponse.json(knowledgeBases.filter((knowledgeBase) => canAccess(knowledgeBase.id))),
  ),
  http.post("/api/knowledge-bases", async ({ request }) => {
    const { name } = (await request.json()) as { name: string };
    if (knowledgeBases.some((knowledgeBase) => knowledgeBase.name === name)) {
      return problem(409, "KNOWLEDGE_CONFLICT", `已有同名知识库：${name}`);
    }
    const created = { id: nextId++, name };
    knowledgeBases.push(created);
    // 建库的人自动成为成员（D-017）。
    members[created.id] = [currentIdentity().id];
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get("/api/knowledge-bases/:id/members", ({ params }) =>
    HttpResponse.json((members[Number(params.id)] ?? []).map((userId) => ({ userId, ...accountName(userId) }))),
  ),
  http.put("/api/knowledge-bases/:id/members/:userId", ({ params }) => {
    const list = (members[Number(params.id)] ??= []);
    if (!list.includes(Number(params.userId))) {
      list.push(Number(params.userId));
    }
    return new HttpResponse(null, { status: 204 });
  }),
  http.delete("/api/knowledge-bases/:id/members/:userId", ({ params }) => {
    members[Number(params.id)] = (members[Number(params.id)] ?? []).filter((id) => id !== Number(params.userId));
    return new HttpResponse(null, { status: 204 });
  }),
  http.get("/api/knowledge-bases/:id/role-grants", ({ params }) =>
    HttpResponse.json((roleGrants[Number(params.id)] ?? []).map((roleId) => ({ roleId }))),
  ),
  http.put("/api/knowledge-bases/:id/role-grants/:roleId", ({ params }) => {
    const list = (roleGrants[Number(params.id)] ??= []);
    if (!list.includes(Number(params.roleId))) list.push(Number(params.roleId));
    return new HttpResponse(null, { status: 204 });
  }),
  http.delete("/api/knowledge-bases/:id/role-grants/:roleId", ({ params }) => {
    roleGrants[Number(params.id)] = (roleGrants[Number(params.id)] ?? []).filter((id) => id !== Number(params.roleId));
    return new HttpResponse(null, { status: 204 });
  }),
  // 模拟后台入库：解析中的文档被查询一次之后变成可用；它若是新版本，旧版本随之删除。
  http.get("/api/knowledge-bases/:id/documents", ({ params, request }) => {
    const id = Number(params.id);
    const list = documents[id] ?? [];
    const url = new URL(request.url);
    const keyword = url.searchParams.get("keyword");
    const status = url.searchParams.get("status");
    const snapshot = paginate(
      list
        .filter((document) => matches(keyword, document.filename))
        .filter((document) => !status || document.status === status)
        .map((document) => ({
          ...document,
          replacesFilename: list.find((other) => other.id === document.replacesDocumentId)?.filename ?? null,
        })),
      url,
    );
    for (const document of list.filter((candidate) => candidate.status === "PARSING")) {
      Object.assign(document, { status: "READY", chunkCount: pendingChunks.get(document.id) ?? 1 });
      if (document.replacesDocumentId) {
        documents[id] = (documents[id] ?? []).filter((candidate) => candidate.id !== document.replacesDocumentId);
        document.replacesDocumentId = null;
      }
    }
    return HttpResponse.json(snapshot);
  }),
  http.delete("/api/knowledge-bases/:id/documents/:documentId", ({ params }) => {
    const id = Number(params.id);
    const before = documents[id]?.length ?? 0;
    documents[id] = (documents[id] ?? []).filter((document) => document.id !== Number(params.documentId));
    return documents[id].length < before
      ? new HttpResponse(null, { status: 204 })
      : problem(404, "KNOWLEDGE_NOT_FOUND", "文档不存在");
  }),
  // 与后端一致：只收 PDF、Word、Excel、TXT、Markdown，同名文件视为重复。
  http.post("/api/knowledge-bases/:id/documents", async ({ params, request }) => {
    const file = await uploadedFile(request);
    if (!/\.(txt|md|markdown|pdf|docx|xlsx|xls)$/i.test(file.name)) {
      return problem(
        415,
        "KNOWLEDGE_UNSUPPORTED_TYPE",
        "目前支持 PDF、Word（.docx）、Excel（.xlsx / .xls）、TXT 和 Markdown；旧版 .doc 请先另存为 .docx",
      );
    }
    const list = (documents[Number(params.id)] ??= []);
    if (list.some((document) => document.filename === file.name)) {
      return problem(409, "KNOWLEDGE_CONFLICT", `这份文件已经在知识库里了：${file.name}`);
    }
    const created: KnowledgeDocument = {
      id: nextId++,
      filename: file.name,
      status: "PARSING",
      error: null,
      chunkCount: 0,
      sizeBytes: file.size,
      createdAt: new Date().toISOString(),
      replacesDocumentId: Number(new URL(request.url).searchParams.get("replaces")) || null,
      replacesFilename: null,
    };
    list.unshift(created);
    pendingChunks.set(created.id, Math.max(1, Math.ceil(file.size / 1_500)));
    return HttpResponse.json(created, { status: 201 });
  }),
  ...conversationHandlers({
    currentUserId: () => currentIdentity().id,
    knowledgeBaseName: (id) => knowledgeBases.find((knowledgeBase) => knowledgeBase.id === id)?.name ?? `知识库 ${id}`,
    canAccess,
    accessibleKnowledgeBaseIds: () =>
      knowledgeBases.filter((knowledgeBase) => canAccess(knowledgeBase.id)).map((knowledgeBase) => knowledgeBase.id),
  }),
];
