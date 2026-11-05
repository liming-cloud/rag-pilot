import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "@/mocks/server";
import { markReview, recheckAnswer } from "./api";

describe("回答记录接口的失败路径（#145 C8）", () => {
  it("标记处理、重新回答被拒绝时抛出服务端给的原因", async () => {
    server.use(
      http.all("/api/answers/*", () =>
        HttpResponse.json({ status: 400, code: "ANSWERING_INVALID", detail: "说明不能超过 500 字" }, { status: 400 }),
      ),
    );

    await expect(markReview(1, "RESOLVED", "长".repeat(501))).rejects.toMatchObject({
      code: "ANSWERING_INVALID",
      message: "说明不能超过 500 字",
    });
    await expect(recheckAnswer(1)).rejects.toThrow("说明不能超过 500 字");
  });

  it("服务端没给原因时用默认说明", async () => {
    server.use(http.all("/api/answers/*", () => new HttpResponse(null, { status: 502 })));

    await expect(markReview(1, "OPEN")).rejects.toThrow("保存处理状态失败");
  });
});
