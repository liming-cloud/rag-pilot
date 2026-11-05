import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/mocks/server";
import { deleteDocument, setMember, setRoleGrant, updateValidity, uploadDocument } from "./api";

const conflict = () =>
  HttpResponse.json({ status: 409, code: "KNOWLEDGE_CONFLICT", detail: "这份文件已经在库里了" }, { status: 409 });

describe("知识库接口的失败路径（#145 C8）", () => {
  it.each([
    ["授权给账号", () => setMember(1, 2, true)],
    ["撤销账号授权", () => setMember(1, 2, false)],
    ["授权给角色", () => setRoleGrant(1, 3, true)],
    ["收回角色授权", () => setRoleGrant(1, 3, false)],
    ["删除文档", () => deleteDocument(1, 11)],
    ["上传文档", () => uploadDocument(1, new File(["x"], "a.txt"))],
    ["上传新版本", () => uploadDocument(1, new File(["x"], "a.txt"), 11, { effectiveFrom: null, expiresOn: null })],
    ["改有效期", () => updateValidity(1, 11, { effectiveFrom: null, expiresOn: "2026-12-31" })],
  ])("%s：服务端拒绝时抛出它给的原因和错误码", async (_, call) => {
    server.use(http.all("/api/knowledge-bases/*", conflict));

    await expect(call()).rejects.toMatchObject({
      status: 409,
      code: "KNOWLEDGE_CONFLICT",
      message: "这份文件已经在库里了",
    });
  });

  it("服务端没给原因时用默认说明；403 统一提示联系管理员", async () => {
    server.use(http.all("/api/knowledge-bases/*", () => new HttpResponse("oops", { status: 500 })));
    await expect(setMember(1, 2, false)).rejects.toThrow("撤销失败");
    await expect(setRoleGrant(1, 3, false)).rejects.toThrow("收回失败");

    server.use(http.all("/api/knowledge-bases/*", () => HttpResponse.json({ detail: "x" }, { status: 403 })));
    await expect(deleteDocument(1, 11)).rejects.toThrow("没有权限执行此操作，请联系管理员");
  });
});
