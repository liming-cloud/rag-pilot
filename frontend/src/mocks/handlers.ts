import { http, HttpResponse } from "msw";
import type { AnswerReview } from "@/features/answers/api";
import type { Answer } from "@/features/chat/api";
import type { KnowledgeBase, KnowledgeDocument, Validity } from "@/features/knowledge/api";
import type { UsageStats } from "@/features/stats/api";
import { auditHandlers } from "./audit";
import { conversationHandlers, resetConversations } from "./conversations";
import { gapHandlers, resetGaps, topGaps } from "./gaps";
import { dataHandlers, resetData } from "./data";
import { accountName, currentIdentity, forbidden, identityHandlers, resetIdentity } from "./identity";
import { modelHandlers, resetModels } from "./models";
import { matches, paginate } from "./page";
import { resetSettings, settingHandlers } from "./settings";

/** 假接口与后端契约保持一致；示例数据只用于原型和测试。 */
export const sampleKnowledgeBases: KnowledgeBase[] = [
  { id: 1, name: "门店运营知识库" },
  { id: 2, name: "商品知识库" },
  // 多生成一些知识库，原型里能看到分页和筛选；只有管理员是成员。
  ...Array.from({ length: 22 }, (_, index) => ({ id: 3 + index, name: `区域 ${index + 1} 门店规范` })),
];

/** 存储的文档不带 validity，读取时按今天现算，与后端一致。 */
type StoredDocument = Omit<KnowledgeDocument, "validity">;

/** 提前多少天提醒到期，对应部署配置 rag.knowledge.expiry-warning-days（默认 30）。 */
const WARNING_DAYS = 30;

/** 统计时区（默认北京时间）的今天，yyyy-MM-dd。 */
function today() {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date());
}

function addDays(date: string, days: number) {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + days);
  return next.toISOString().slice(0, 10);
}

/** 失效日期当天仍有效；未到生效日期的不参与检索。 */
function validityOf(document: StoredDocument): Validity {
  const now = today();
  if (document.effectiveFrom && document.effectiveFrom > now) return "PENDING";
  if (document.expiresOn && document.expiresOn < now) return "EXPIRED";
  if (document.expiresOn && document.expiresOn <= addDays(now, WARNING_DAYS)) return "EXPIRING";
  return "ACTIVE";
}

const withValidity = (document: StoredDocument): KnowledgeDocument => ({ ...document, validity: validityOf(document) });

const longTerm = { effectiveFrom: null, expiresOn: null };

const sampleDocuments: Record<number, StoredDocument[]> = {
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
      reprocessable: false,
      retryAt: null,
      ...longTerm,
    },
    // 有效期的几种情况（日期相对今天，原型里随时都能看到）：旧版制度已失效、今天到期（当天仍有效）、
    // 月底到期的促销规则、下周才生效的新制度。
    {
      id: 13,
      filename: "临期与报损管理（旧版）.md",
      status: "READY",
      error: null,
      chunkCount: 10,
      sizeBytes: 16_200,
      createdAt: "2026-03-01T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      effectiveFrom: "2026-03-01",
      expiresOn: addDays(today(), -10),
    },
    {
      id: 14,
      filename: "双十一活动执行方案.pdf",
      status: "READY",
      error: null,
      chunkCount: 6,
      sizeBytes: 220_000,
      createdAt: "2026-10-20T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      effectiveFrom: null,
      expiresOn: today(),
    },
    {
      id: 15,
      filename: "会员积分规则（本季度）.docx",
      status: "READY",
      error: null,
      chunkCount: 9,
      sizeBytes: 64_000,
      createdAt: "2026-10-01T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      effectiveFrom: null,
      expiresOn: addDays(today(), 18),
    },
    {
      id: 16,
      filename: "收银交接班制度（下周起执行）.md",
      status: "READY",
      error: null,
      chunkCount: 5,
      sizeBytes: 12_000,
      createdAt: "2026-10-30T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      effectiveFrom: addDays(today(), 5),
      expiresOn: null,
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
      reprocessable: false,
      retryAt: null,
      ...longTerm,
    },
    // 原型（#145 A9）：模型暂时不可用而失败的文档，可以重新处理，10 分钟后自动重试一次。
    {
      id: 17,
      filename: "夜班交接手册.docx",
      status: "FAILED",
      error: "向量化失败：嵌入模型服务暂时不可用，请稍后重试",
      chunkCount: 0,
      sizeBytes: 52_224,
      createdAt: "2026-11-04T09:30:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: true,
      retryAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
      ...longTerm,
    },
    // 多生成一些文档，原型里能看到分页。
    ...Array.from({ length: 30 }, (_, index): StoredDocument => ({
      id: 300 + index,
      filename: `门店操作手册-第${index + 1}章.pdf`,
      status: "READY",
      error: null,
      chunkCount: 8 + (index % 7),
      sizeBytes: 120_000 + index * 9_000,
      createdAt: "2026-10-27T10:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      ...longTerm,
    })),
  ],
};

/** 假接口的可变数据；测试之间用 resetMockData 还原。 */
let knowledgeBases: KnowledgeBase[] = [];
let documents: Record<number, StoredDocument[]> = {};
let members: Record<number, number[]> = {};
/** 知识库授权给了哪些角色（D-038）。 */
let roleGrants: Record<number, number[]> = {};
const pendingChunks = new Map<number, number>();
let nextId = 100;

export function resetMockData() {
  resetGaps();
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
  resetModels();
  resetData();
  resetSettings();
  resetConversations();
}

/**
 * 从 multipart 请求体里读出文件名、大小和其他表单字段。不用 request.formData()：Node 24 在 jsdom 测试环境里解析时，
 * 会用 jsdom 的 File 构造对象而触发 undici 的断言。
 */
async function uploadedFile(request: Request) {
  const body = await request.text();
  const boundary = /boundary=(.+)$/.exec(request.headers.get("Content-Type") ?? "")?.[1] ?? "";
  const parts = body.split(`--${boundary}`).filter((part) => part.includes("\r\n\r\n"));
  const fields: Record<string, string> = {};
  let name = "";
  let size = 0;
  for (const part of parts) {
    const [head, ...rest] = part.split("\r\n\r\n");
    const content = rest.join("\r\n\r\n").replace(/\r\n$/, "");
    const filename = /filename="([^"]*)"/.exec(head!)?.[1];
    if (filename !== undefined) {
      name = filename;
      size = new TextEncoder().encode(content).length;
    } else {
      fields[/name="([^"]*)"/.exec(head!)?.[1] ?? ""] = content;
    }
  }
  return { name, size, fields };
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

// 与后端一致：RFC 9457 问题详情，带稳定的错误码 code（见规则卡 A-19、D-034）。
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

/** 当前账号能不能访问这个知识库：直接授权，或所属角色被授权（D-038）。 */
function canAccess(knowledgeBaseId: number) {
  const { id, roleIds } = currentIdentity();
  return (
    (members[knowledgeBaseId]?.includes(id) ?? false) ||
    (roleGrants[knowledgeBaseId] ?? []).some((roleId) => roleIds.includes(roleId))
  );
}

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

async function upload(knowledgeBaseId: number, request: Request, replacesDocumentId: number | null) {
  const file = await uploadedFile(request);
  if (!/\.(txt|md|markdown|pdf|docx|xlsx|xls)$/i.test(file.name)) {
    return problem(
      415,
      "KNOWLEDGE_UNSUPPORTED_TYPE",
      "目前支持 PDF、Word（.docx）、Excel（.xlsx / .xls）、TXT 和 Markdown；旧版 .doc 请先另存为 .docx",
    );
  }
  const list = (documents[knowledgeBaseId] ??= []);
  if (list.some((document) => document.filename === file.name)) {
    return problem(409, "KNOWLEDGE_CONFLICT", `这份文件已经在知识库里了：${file.name}`);
  }
  // 上传新版本时没给有效期，就继承旧版本的失效日期（#124）。
  const previous = list.find((document) => document.id === replacesDocumentId);
  const given = "expiresOn" in file.fields;
  const range = {
    effectiveFrom: given ? file.fields.effectiveFrom || null : null,
    expiresOn: given ? file.fields.expiresOn || null : (previous?.expiresOn ?? null),
  };
  const invalid = invalidRange(range);
  if (invalid) return invalid;
  const created: StoredDocument = {
    id: nextId++,
    filename: file.name,
    status: "PARSING",
    error: null,
    chunkCount: 0,
    sizeBytes: file.size,
    createdAt: new Date().toISOString(),
    replacesDocumentId,
    replacesFilename: null,
    reprocessable: false,
    retryAt: null,
    ...range,
  };
  list.unshift(created);
  pendingChunks.set(created.id, Math.max(1, Math.ceil(file.size / 1_500)));
  return HttpResponse.json(withValidity(created), { status: 201 });
}

function invalidRange(range: { effectiveFrom: string | null; expiresOn: string | null }) {
  return range.effectiveFrom && range.expiresOn && range.expiresOn < range.effectiveFrom
    ? problem(400, "KNOWLEDGE_INVALID", "失效日期不能早于生效日期")
    : null;
}

export const handlers = [
  ...identityHandlers,
  ...auditHandlers,
  ...gapHandlers,
  ...modelHandlers,
  ...dataHandlers,
  ...settingHandlers,
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
  // 原型（#145 A11）：员工点开了第几个引用，接接口后由后端记下。
  http.post("/api/answers/:id/citations/:index/opened", () => new HttpResponse(null, { status: 204 })),
  http.put("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  http.delete("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  // 与后端一致：只返回当前账号是成员的知识库，管理员也一样（D-017）。
  // 管理端列表：要放在 /:id 之前，免得 page 被当成 id。
  http.get("/api/knowledge-bases/page", ({ request }) => {
    const url = new URL(request.url);
    const keyword = url.searchParams.get("keyword");
    const validity = url.searchParams.get("validity");
    const count = (id: number, state: Validity) =>
      (documents[id] ?? []).filter((document) => validityOf(document) === state).length;
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
            expiringCount: count(knowledgeBase.id, "EXPIRING"),
            expiredCount: count(knowledgeBase.id, "EXPIRED"),
          }))
          .filter(
            (summary) => !validity || (validity === "EXPIRING" ? summary.expiringCount > 0 : summary.expiredCount > 0),
          ),
        url,
      ),
    );
  }),
  // 运营概览的到期提醒：只算自己能访问的库，与列表口径一致。
  http.get("/api/knowledge-bases/expiry-summary", () => {
    const visible = knowledgeBases
      .filter((knowledgeBase) => canAccess(knowledgeBase.id))
      .flatMap((knowledgeBase) =>
        (documents[knowledgeBase.id] ?? []).map((document) => ({
          knowledgeBase,
          document,
          state: validityOf(document),
        })),
      );
    const flagged = visible
      .filter(({ state }) => state === "EXPIRING" || state === "EXPIRED")
      .sort((a, b) => a.document.expiresOn!.localeCompare(b.document.expiresOn!));
    return HttpResponse.json({
      expiring: flagged.filter(({ state }) => state === "EXPIRING").length,
      expired: flagged.filter(({ state }) => state === "EXPIRED").length,
      warningDays: WARNING_DAYS,
      documents: flagged.slice(0, 20).map(({ knowledgeBase, document, state }) => ({
        knowledgeBaseId: knowledgeBase.id,
        knowledgeBaseName: knowledgeBase.name,
        documentId: document.id,
        filename: document.filename,
        expiresOn: document.expiresOn,
        validity: state,
      })),
    });
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
    const validity = url.searchParams.get("validity");
    const snapshot = paginate(
      list
        .filter((document) => matches(keyword, document.filename))
        .filter((document) => !status || document.status === status)
        .filter((document) => !validity || validityOf(document) === validity)
        .map((document) => ({
          ...withValidity(document),
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
  http.post("/api/knowledge-bases/:id/documents/:documentId/reprocess", ({ params }) => {
    const document = (documents[Number(params.id)] ?? []).find((item) => item.id === Number(params.documentId));
    if (!document) return HttpResponse.json({ status: 404, code: "KNOWLEDGE_NOT_FOUND", detail: "文档不存在" }, { status: 404 });
    if (document.status !== "FAILED" || !document.reprocessable) {
      return HttpResponse.json(
        {
          status: 409,
          code: "KNOWLEDGE_CONFLICT",
          detail: "只有因模型暂时不可用而失败的文档才能重新处理；文件本身有问题时请改好后重新上传",
        },
        { status: 409 },
      );
    }
    Object.assign(document, { status: "PARSING", error: null, reprocessable: false, retryAt: null });
    pendingChunks.set(document.id, 6);
    return HttpResponse.json(withValidity(document));
  }),
  http.delete("/api/knowledge-bases/:id/documents/:documentId", ({ params }) => {
    const id = Number(params.id);
    const before = documents[id]?.length ?? 0;
    documents[id] = (documents[id] ?? []).filter((document) => document.id !== Number(params.documentId));
    return documents[id].length < before
      ? new HttpResponse(null, { status: 204 })
      : problem(404, "KNOWLEDGE_NOT_FOUND", "文档不存在");
  }),
  http.put("/api/knowledge-bases/:id/documents/:documentId/validity", async ({ params, request }) => {
    const denied = forbidden("doc:update");
    if (denied) return denied;
    const document = documents[Number(params.id)]?.find((candidate) => candidate.id === Number(params.documentId));
    if (!document) return problem(404, "KNOWLEDGE_NOT_FOUND", "文档不存在");
    const range = (await request.json()) as { effectiveFrom: string | null; expiresOn: string | null };
    const invalid = invalidRange(range);
    if (invalid) return invalid;
    Object.assign(document, { effectiveFrom: range.effectiveFrom || null, expiresOn: range.expiresOn || null });
    return HttpResponse.json(withValidity(document));
  }),
  // 与后端一致：只收 PDF、Word、Excel、TXT、Markdown，同名文件视为重复。
  http.post("/api/knowledge-bases/:id/documents", ({ params, request }) => upload(Number(params.id), request, null)),
  // 上传新版本是单独的接口（doc:replace），与后端一致。
  http.post("/api/knowledge-bases/:id/documents/:documentId/versions", ({ params, request }) =>
    upload(Number(params.id), request, Number(params.documentId)),
  ),
  ...conversationHandlers({
    currentUserId: () => currentIdentity().id,
    knowledgeBaseName: (id) => knowledgeBases.find((knowledgeBase) => knowledgeBase.id === id)?.name ?? `知识库 ${id}`,
    canAccess,
    accessibleKnowledgeBaseIds: () =>
      knowledgeBases.filter((knowledgeBase) => canAccess(knowledgeBase.id)).map((knowledgeBase) => knowledgeBase.id),
  }),
];
