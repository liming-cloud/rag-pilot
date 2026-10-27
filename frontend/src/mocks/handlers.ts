import { http, HttpResponse } from "msw";
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

/** 假接口的可变数据；测试之间用 resetMockData 还原。 */
let knowledgeBases: KnowledgeBase[] = [];
let documents: Record<number, KnowledgeDocument[]> = {};
let nextId = 100;

export function resetMockData() {
  knowledgeBases = sampleKnowledgeBases.map((knowledgeBase) => ({ ...knowledgeBase }));
  documents = Object.fromEntries(Object.entries(sampleDocuments).map(([id, list]) => [id, [...list]]));
}
resetMockData();

const problem = (status: number, detail: string) => HttpResponse.json({ status, detail }, { status });

export const sampleAnswer: Answer = {
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

const sampleUser = { id: 2, username: "staff01", displayName: "店员小王", role: "STAFF" };

export const handlers = [
  // 密码填 wrong-password 时模拟登录失败，其他任意账号密码都能登录。
  http.post("/api/auth/login", async ({ request }) => {
    const { password } = (await request.json()) as { password: string };
    return password === "wrong-password"
      ? HttpResponse.json({ detail: "用户名或密码错误" }, { status: 401 })
      : HttpResponse.json({ token: "mock-token", user: sampleUser });
  }),
  http.get("/api/auth/me", () => HttpResponse.json(sampleUser)),
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
  http.get("/api/knowledge-bases/:id/documents", ({ params }) => HttpResponse.json(documents[Number(params.id)] ?? [])),
  // 与后端一致：只收 TXT / Markdown，同名文件视为重复。
  http.post("/api/knowledge-bases/:id/documents", async ({ params, request }) => {
    const file = (await request.formData()).get("file") as File;
    if (!/\.(txt|md|markdown)$/i.test(file.name)) {
      return problem(415, "目前只支持 TXT 和 Markdown；PDF、Word、PPT、Excel 将在接入文档解析服务后支持");
    }
    const list = (documents[Number(params.id)] ??= []);
    if (list.some((document) => document.filename === file.name)) {
      return problem(409, `这份文件已经在知识库里了：${file.name}`);
    }
    const created: KnowledgeDocument = {
      id: nextId++,
      filename: file.name,
      status: "READY",
      error: null,
      chunkCount: Math.max(1, Math.ceil(file.size / 1_500)),
      sizeBytes: file.size,
      createdAt: new Date().toISOString(),
    };
    list.unshift(created);
    return HttpResponse.json(created, { status: 201 });
  }),
  http.post("/api/ask/stream", () =>
    sseResponse([
      ...(sampleAnswer.text.match(/.{1,12}/gu) ?? []).map((text): [string, unknown] => ["delta", { text }]),
      ["done", sampleAnswer],
    ]),
  ),
];
