import { ApiError, json } from "@/lib/api";

describe("接口错误", () => {
  it("把问题详情的说明和错误码交给调用方", async () => {
    const response = new Response(JSON.stringify({ status: 409, code: "KNOWLEDGE_CONFLICT", detail: "已有同名知识库：门店规范" }), {
      status: 409,
    });

    const error = await json(response, "保存失败").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: "KNOWLEDGE_CONFLICT", message: "已有同名知识库：门店规范" });
  });

  it("响应不是问题详情时用调用方给的说明，没有错误码", async () => {
    const error = await json(new Response("bad gateway", { status: 502 }), "保存失败").catch((e: unknown) => e);

    expect(error).toMatchObject({ status: 502, code: undefined, message: "保存失败" });
  });
});
