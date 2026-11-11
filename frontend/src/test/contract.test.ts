import { describe, expect, it } from "vitest";
import { sseParser } from "@/features/chat/sse";
import { PERMISSIONS } from "@/lib/permissions";
import { streamTiming } from "@/mocks/conversations";
import { signInAs } from "@/mocks/identity";
import { mismatches, shapeOf, type Shape } from "./contract";
import shapes from "./contract/api-shapes.json";

// 后端接口路径里的 :id 换成假数据里存在的标识。
const MOCK_PATHS: Record<string, string> = {
  "GET /api/knowledge-bases/:id": "/api/knowledge-bases/1",
  "GET /api/knowledge-bases/:id/documents": "/api/knowledge-bases/1/documents",
  "GET /api/knowledge-bases/:id/members": "/api/knowledge-bases/1/members",
  "GET /api/knowledge-bases/:id/role-grants": "/api/knowledge-bases/1/role-grants",
  "GET /api/conversations/:id": "/api/conversations/901",
  "GET /api/data-grants": "/api/data-grants?granteeType=USER&granteeId=2",
  "GET /api/answers": "/api/answers?filter=all&status=all",
  "GET /api/answers/gaps": "/api/answers/gaps?days=30&status=all",
};

// 后端样例按管理员调用；示例会话属于店员小王，会话接口用他的身份取。
const MOCK_USERS: Record<string, string> = {
  "GET /api/conversations": "staff01",
  "GET /api/conversations/:id": "staff01",
};

/**
 * 写接口怎么调假接口：路径里的标识换成假数据里有的，请求体照前端的写法；file 表示上传一个文件；
 * before 是调用前要先造的数据。
 */
type WriteCall = { path: string; body?: unknown; file?: true; user?: string; before?: () => Promise<unknown> };

const json = (path: string, body: unknown) =>
  fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

const importRows = [{ line: 2, username: "contract02", displayName: "契约导入", roleNames: ["员工"] }];
const provider = { name: "契约服务商", baseUrl: "http://127.0.0.1:9/v1", apiKey: "sk-contract-0000" };

const WRITE_CALLS: Record<string, WriteCall> = {
  "POST /api/auth/login": { path: "/api/auth/login", body: { username: "staff01", password: "staff-password" } },
  "POST /api/roles": { path: "/api/roles", body: { name: "契约店长", permissions: ["kb:view"] } },
  "PATCH /api/roles/:id": { path: "/api/roles/4", body: { name: "店长", permissions: ["chat:use"] } },
  // 示例角色都有账号，删不掉；先建一个没人用的（新建的 id 从 200 起）。
  "DELETE /api/roles/:id": { path: "/api/roles/200", before: () => json("/api/roles", { name: "临时", permissions: [] }) },
  "POST /api/users": {
    path: "/api/users",
    body: { username: "contract01", displayName: "契约店员", password: "staff-password", roleIds: [3] },
  },
  "PUT /api/users/:id/enabled": { path: "/api/users/2/enabled", body: { enabled: true } },
  "PUT /api/users/:id/roles": { path: "/api/users/2/roles", body: { roleIds: [3, 4] } },
  "POST /api/users/:id/reset-password": { path: "/api/users/2/reset-password", body: { password: "staff-password-2" } },
  "POST /api/users/:id/unlock": { path: "/api/users/3/unlock" },
  "POST /api/users/import/check": { path: "/api/users/import/check", body: { rows: importRows } },
  "POST /api/users/import": { path: "/api/users/import", body: { rows: importRows, password: "import-password" } },
  "POST /api/knowledge-bases": { path: "/api/knowledge-bases", body: { name: "契约知识库" } },
  "PUT /api/knowledge-bases/:id/members/:userId": { path: "/api/knowledge-bases/1/members/4" },
  "PUT /api/knowledge-bases/:id/role-grants/:roleId": { path: "/api/knowledge-bases/1/role-grants/4" },
  "POST /api/knowledge-bases/:id/documents": { path: "/api/knowledge-bases/1/documents", file: true },
  "POST /api/knowledge-bases/:id/documents/:documentId/versions": {
    path: "/api/knowledge-bases/1/documents/11/versions",
    file: true,
  },
  "PUT /api/knowledge-bases/:id/documents/:documentId/validity": {
    path: "/api/knowledge-bases/1/documents/11/validity",
    body: { effectiveFrom: "", expiresOn: "2099-01-01" },
  },
  "POST /api/knowledge-bases/:id/documents/:documentId/reprocess": {
    path: "/api/knowledge-bases/1/documents/17/reprocess",
  },
  "DELETE /api/knowledge-bases/:id/documents/:documentId": { path: "/api/knowledge-bases/1/documents/11" },
  "POST /api/conversations": { path: "/api/conversations", body: {}, user: "staff01" },
  "PATCH /api/conversations/:id": { path: "/api/conversations/901", body: { title: "交班" }, user: "staff01" },
  "DELETE /api/conversations/:id": { path: "/api/conversations/901", user: "staff01" },
  "PUT /api/answers/:id/feedback": {
    path: "/api/answers/701/feedback",
    body: { rating: "DOWN", comment: "没说要签字" },
    user: "staff01",
  },
  "DELETE /api/answers/:id/feedback": { path: "/api/answers/701/feedback", user: "staff01" },
  "POST /api/answers/:id/citations/:index/opened": { path: "/api/answers/701/citations/1/opened", user: "staff01" },
  "PATCH /api/answers/:id/review": { path: "/api/answers/601/review", body: { status: "RESOLVED", note: "已补" } },
  "POST /api/answers/:id/recheck": { path: "/api/answers/601/recheck" },
  "POST /api/answers/gaps/resolve": { path: "/api/answers/gaps/resolve", body: { recordIds: [601], note: "已补" } },
  "PUT /api/settings/:key": { path: "/api/settings/CHAT_PLACEHOLDER", body: { value: "问点规范" } },
  "DELETE /api/settings/:key": { path: "/api/settings/LOGIN_HINT" },
  "POST /api/model-providers": { path: "/api/model-providers", body: provider },
  "PATCH /api/model-providers/:id": { path: "/api/model-providers/1", body: { ...provider, apiKey: "" } },
  "DELETE /api/model-providers/:id": { path: "/api/model-providers/2" },
  "PUT /api/models/prices": {
    path: "/api/models/prices",
    body: { model: "deepseek-chat", inputPerMillion: 2, outputPerMillion: 8 },
  },
  "PUT /api/data-grants": {
    path: "/api/data-grants?granteeType=USER&granteeId=2",
    body: { tools: [], units: [], regions: [] },
  },
};

const endpoints = shapes.endpoints as Record<string, Shape>;
const writes = shapes.writes as Record<string, { status: number; body?: Shape }>;
const events = shapes.events as Record<string, Shape>;

function send(endpoint: string, call: WriteCall) {
  const method = endpoint.slice(0, endpoint.indexOf(" "));
  if (call.file) {
    const form = new FormData();
    form.append("file", new File(["收银员交班时要清点备用金。"], "契约.txt", { type: "text/plain" }));
    return fetch(call.path, { method, body: form });
  }
  if (call.body === undefined) return fetch(call.path, { method });
  return fetch(call.path, { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(call.body) });
}

/** 在示例会话里流式提问，读完事件流，返回每种事件合并后的数据形状。 */
async function streamShapes(question: string): Promise<Record<string, Shape>> {
  const response = await fetch("/api/conversations/901/turns/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
  const reader = response.body!.pipeThrough(new TextDecoderStream()).getReader();
  const push = sseParser();
  const received: Record<string, unknown[]> = {};
  for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
    for (const event of push(chunk.value)) (received[event.name] ??= []).push(JSON.parse(event.data));
  }
  return Object.fromEntries(Object.entries(received).map(([name, list]) => [name, (shapeOf(list) as Shape[])[0]!]));
}

describe("与后端接口契约一致（#145 C7、#201）", () => {
  it("权限码常量和后端的权限点清单一一对应", () => {
    expect(Object.values(PERMISSIONS).toSorted()).toEqual(shapes.permissions);
  });

  it.each(Object.keys(endpoints))("%s：假数据和后端的字段、类型一致", async (endpoint) => {
    signInAs(MOCK_USERS[endpoint] ?? "admin");
    const path = MOCK_PATHS[endpoint] ?? endpoint.replace(/^GET /, "");
    const response = await fetch(path);
    expect(response.ok, `${path} 返回 ${response.status}`).toBe(true);

    expect(mismatches(endpoints[endpoint]!, shapeOf(await response.json()))).toEqual([]);
  });

  it("契约里的每个写接口都写了怎么调假接口", () => {
    const missing = Object.keys(writes).filter((endpoint) => !(endpoint in WRITE_CALLS));
    expect(missing, "在 contract.test.ts 的 WRITE_CALLS 里补上这些写接口的调用方式").toEqual([]);
  });

  it.each(Object.keys(writes))("%s：假接口的状态码和响应字段、类型与后端一致", async (endpoint) => {
    const call = WRITE_CALLS[endpoint]!;
    signInAs(call.user ?? "admin");
    await call.before?.();
    const response = await send(endpoint, call);
    const text = await response.text();
    const expected = writes[endpoint]!;

    expect(response.status, `${call.path}：${text}（改 mocks/ 里对应的处理器）`).toBe(expected.status);
    if (expected.body === undefined) {
      expect(text, "后端没有响应体").toBe("");
    } else {
      expect(mismatches(expected.body, shapeOf(JSON.parse(text)))).toEqual([]);
    }
  });

  it("流式提问：假接口发的每种 SSE 事件与后端的事件名、字段、类型一致", async () => {
    signInAs("staff01");
    Object.assign(streamTiming, { stepMillis: 0, deltaMillis: 0 });
    const received = { ...(await streamShapes("临期商品怎么处理")), ...(await streamShapes("模型不可用时怎么办")) };

    expect(Object.keys(received).toSorted(), "假接口要发出后端的每种事件（改 mocks/conversations.ts）").toEqual(
      Object.keys(events).toSorted(),
    );
    for (const [name, shape] of Object.entries(events)) {
      expect(mismatches(shape, received[name]!, `事件 ${name}`)).toEqual([]);
    }
  });

  it("假接口的权限点清单就是后端的清单", async () => {
    signInAs("admin");
    const catalog = (await (await fetch("/api/permissions")).json()) as unknown;
    const codes = JSON.stringify(catalog).match(/"code":"([^"]+)"/g)?.map((entry) => entry.slice(8, -1)) ?? [];
    expect(codes.toSorted()).toEqual(shapes.permissions);
  });

  it("比较规则：缺字段、多字段、类型不同都报出来；null 和空数组不比较类型", () => {
    expect(mismatches(shapeOf({ id: 1, name: "a", tags: ["x"] }), shapeOf({ id: "1", extra: true, tags: [] }))).toEqual([
      "响应.name：后端有、假数据没有",
      "响应.extra：假数据有、后端没有",
      "响应.id：后端是 number，假数据是 string",
    ]);
    expect(mismatches(shapeOf([{ at: null }]), shapeOf([{ at: "2026-11-04" }]))).toEqual([]);
  });
});
