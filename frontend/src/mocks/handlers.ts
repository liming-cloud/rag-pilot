import { http, HttpResponse } from "msw";
import type { Answer } from "@/features/chat/api";
import type { KnowledgeBase } from "@/features/knowledge/api";
import { sseResponse } from "./sse";

/** 假接口与后端契约保持一致；示例数据只用于原型和测试。 */
export const sampleKnowledgeBases: KnowledgeBase[] = [
  { id: 1, name: "门店运营知识库" },
  { id: 2, name: "商品知识库" },
];

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
  http.get("/api/knowledge-bases", () => HttpResponse.json(sampleKnowledgeBases)),
  http.post("/api/ask/stream", () =>
    sseResponse([
      ...(sampleAnswer.text.match(/.{1,12}/gu) ?? []).map((text): [string, unknown] => ["delta", { text }]),
      ["done", sampleAnswer],
    ]),
  ),
];
