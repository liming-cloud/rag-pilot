import { describe, expect, it } from "vitest";
import { PERMISSIONS } from "@/lib/permissions";
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
  "GET /api/answers": "/api/answers?filter=all&status=all",
  "GET /api/answers/gaps": "/api/answers/gaps?days=30&status=all",
};

// 后端样例按管理员调用；示例会话属于店员小王，会话接口用他的身份取。
const MOCK_USERS: Record<string, string> = {
  "GET /api/conversations": "staff01",
  "GET /api/conversations/:id": "staff01",
};

const endpoints = shapes.endpoints as Record<string, Shape>;

describe("与后端接口契约一致（#145 C7）", () => {
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
