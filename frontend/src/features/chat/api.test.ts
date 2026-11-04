import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/mocks/server";
import { createConversation, deleteConversation, rateAnswer, stopTurn, updateConversation } from "./api";

describe("问答接口的失败路径（#145 C8）", () => {
  it("评价、删除会话、停止生成失败时报出状态码", async () => {
    server.use(
      http.all("/api/answers/*", () => new HttpResponse(null, { status: 500 })),
      http.all("/api/conversations/*", () => new HttpResponse(null, { status: 404 })),
    );

    await expect(rateAnswer(1, "UP")).rejects.toThrow("反馈失败（500）");
    await expect(rateAnswer(1, null)).rejects.toThrow("反馈失败（500）");
    await expect(deleteConversation(1)).rejects.toThrow("删除会话失败（404）");
    await expect(stopTurn(1, 2)).rejects.toThrow("停止失败（404）");
  });

  it("新建、修改会话被拒绝时抛出服务端给的原因", async () => {
    const invalid = () =>
      HttpResponse.json({ status: 400, code: "ANSWERING_INVALID", detail: "没有可以提问的知识库" }, { status: 400 });
    server.use(http.post("/api/conversations", invalid), http.patch("/api/conversations/:id", invalid));

    await expect(createConversation([9])).rejects.toMatchObject({ code: "ANSWERING_INVALID" });
    await expect(updateConversation(1, { title: "改名" })).rejects.toThrow("没有可以提问的知识库");
  });
});
