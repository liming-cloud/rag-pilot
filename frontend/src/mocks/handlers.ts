import { http, HttpResponse } from "msw";
import type { User } from "@/features/auth/api";
import type { Answer } from "@/features/chat/api";
import type { KnowledgeBase, KnowledgeDocument } from "@/features/knowledge/api";
import { sseResponse } from "./sse";

/** 假接口与后端契约保持一致；示例数据只用于原型和测试。 */
export const sampleKnowledgeBases: KnowledgeBase[] = [
  { id: 1, name: "门店运营知识库" },
  { id: 2, name: "商品知识库" },
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
    },
    {
      id: 12,
      filename: "旧版陈列手册.txt",
      status: "FAILED",
      error: "文件不是 UTF-8 编码，请用“另存为 UTF-8”后重新上传",
      chunkCount: 0,
      sizeBytes: 40_960,
      createdAt: "2026-10-26T09:05:00+08:00",
    },
  ],
};

const sampleUsers: User[] = [
  { id: 1, username: "admin", displayName: "管理员", role: "ADMIN", enabled: true },
  { id: 2, username: "staff01", displayName: "店员小王", role: "STAFF", enabled: true },
  { id: 3, username: "staff02", displayName: "店员小李", role: "STAFF", enabled: true },
];

/** 假接口的可变数据；测试之间用 resetMockData 还原。 */
let knowledgeBases: KnowledgeBase[] = [];
let documents: Record<number, KnowledgeDocument[]> = {};
let members: Record<number, number[]> = {};
let users: User[] = [];
const pendingChunks = new Map<number, number>();
let nextId = 100;

export function resetMockData() {
  knowledgeBases = sampleKnowledgeBases.map((knowledgeBase) => ({ ...knowledgeBase }));
  documents = Object.fromEntries(Object.entries(sampleDocuments).map(([id, list]) => [id, [...list]]));
  members = { 1: [1, 2], 2: [1] };
  users = sampleUsers.map((user) => ({ ...user }));
}
resetMockData();

/**
 * 从 multipart 请求体里读出文件名和大小。不用 request.formData()：Node 24 在 jsdom 测试环境里解析时，
 * 会用 jsdom 的 File 构造对象而触发 undici 的断言。
 */
async function uploadedFile(request: Request) {
  const body = await request.text();
  const name = /filename="([^"]*)"/.exec(body)?.[1] ?? "";
  const content = body.split("\r\n\r\n").slice(1).join("\r\n\r\n").replace(/\r\n--[^\r\n]*--\r\n$/, "");
  return { name, size: new TextEncoder().encode(content).length };
}

const problem = (status: number, detail: string) => HttpResponse.json({ status, detail }, { status });

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

const sampleUser = sampleUsers[1];

export const handlers = [
  // 密码填 wrong-password 时模拟登录失败，其他任意账号密码都能登录。
  http.post("/api/auth/login", async ({ request }) => {
    const { password } = (await request.json()) as { password: string };
    return password === "wrong-password"
      ? HttpResponse.json({ detail: "用户名或密码错误" }, { status: 401 })
      : HttpResponse.json({ token: "mock-token", user: sampleUser });
  }),
  http.get("/api/auth/me", () => HttpResponse.json(sampleUser)),
  http.put("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  http.delete("/api/answers/:id/feedback", () => new HttpResponse(null, { status: 204 })),
  http.get("/api/knowledge-bases", () => HttpResponse.json(knowledgeBases)),
  http.post("/api/knowledge-bases", async ({ request }) => {
    const { name } = (await request.json()) as { name: string };
    if (knowledgeBases.some((knowledgeBase) => knowledgeBase.name === name)) {
      return problem(409, `已有同名知识库：${name}`);
    }
    const created = { id: nextId++, name };
    knowledgeBases.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get("/api/users", () => HttpResponse.json(users)),
  http.post("/api/users", async ({ request }) => {
    const input = (await request.json()) as Omit<User, "id" | "enabled">;
    if (users.some((user) => user.username === input.username)) {
      return problem(409, `用户名已被使用：${input.username}`);
    }
    const created: User = { id: nextId++, username: input.username, displayName: input.displayName, role: input.role, enabled: true };
    users.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),
  // 假定当前登录的是 1 号管理员，与后端一样不能停用自己。
  http.patch("/api/users/:id", async ({ params, request }) => {
    const { enabled } = (await request.json()) as { enabled: boolean };
    const user = users.find((candidate) => candidate.id === Number(params.id));
    if (!user) {
      return problem(404, "账号不存在");
    }
    if (!enabled && user.id === 1) {
      return problem(400, "不能停用自己的账号");
    }
    user.enabled = enabled;
    return HttpResponse.json(user);
  }),
  http.get("/api/knowledge-bases/:id/members", ({ params }) =>
    HttpResponse.json((members[Number(params.id)] ?? []).map((userId) => ({ userId }))),
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
  // 模拟后台入库：解析中的文档被查询一次之后变成可用。
  http.get("/api/knowledge-bases/:id/documents", ({ params }) => {
    const list = documents[Number(params.id)] ?? [];
    const snapshot = list.map((document) => ({ ...document }));
    for (const document of list.filter((candidate) => candidate.status === "PARSING")) {
      Object.assign(document, { status: "READY", chunkCount: pendingChunks.get(document.id) ?? 1 });
    }
    return HttpResponse.json(snapshot);
  }),
  // 与后端一致：只收 PDF、Word、Excel、TXT、Markdown，同名文件视为重复。
  http.post("/api/knowledge-bases/:id/documents", async ({ params, request }) => {
    const file = await uploadedFile(request);
    if (!/\.(txt|md|markdown|pdf|docx|xlsx|xls)$/i.test(file.name)) {
      return problem(415, "目前支持 PDF、Word（.docx）、Excel（.xlsx / .xls）、TXT 和 Markdown；旧版 .doc 请先另存为 .docx");
    }
    const list = (documents[Number(params.id)] ??= []);
    if (list.some((document) => document.filename === file.name)) {
      return problem(409, `这份文件已经在知识库里了：${file.name}`);
    }
    const created: KnowledgeDocument = {
      id: nextId++,
      filename: file.name,
      status: "PARSING",
      error: null,
      chunkCount: 0,
      sizeBytes: file.size,
      createdAt: new Date().toISOString(),
    };
    list.unshift(created);
    pendingChunks.set(created.id, Math.max(1, Math.ceil(file.size / 1_500)));
    return HttpResponse.json(created, { status: 201 });
  }),
  http.post("/api/ask/stream", () =>
    sseResponse([
      ...(sampleAnswer.text.match(/.{1,12}/gu) ?? []).map((text): [string, unknown] => ["delta", { text }]),
      ["done", sampleAnswer],
    ]),
  ),
];
