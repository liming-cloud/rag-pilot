import { ApiError } from "@/lib/api";
import { type BatchItem, classify, prepare, run, tally } from "./batchUpload";

describe("批量上传", () => {
  it("同时最多传 3 个，一个失败不影响其他", async () => {
    let active = 0;
    let peak = 0;
    const items = prepare(Array.from({ length: 8 }, (_, index) => new File(["x"], `${index}.md`)));
    const results = new Map<string, Partial<BatchItem>>();
    await run(
      items,
      async (file) => {
        active++;
        peak = Math.max(peak, active);
        await new Promise((resolve) => setTimeout(resolve, 5));
        active--;
        if (file.name === "3.md") throw new Error("网络中断");
      },
      (key, change) => results.set(key, { ...results.get(key), ...change }),
    );

    expect(peak).toBe(3);
    const done = items.map((item) => ({ ...item, ...results.get(item.key) }));
    expect(tally(done)).toMatchObject({ total: 8, done: 8, success: 7, failed: 1 });
  });

  it("不支持的格式在前端直接标出，不上传；系统文件直接跳过", async () => {
    const items = prepare([
      new File(["x"], "a.jpg"),
      new File(["x"], "b.PDF"),
      new File(["x"], "c.doc"),
      new File(["x"], ".DS_Store"),
      new File(["x"], "~$草稿.docx"),
      new File(["x"], "Thumbs.db"),
    ]);
    expect(items.map((item) => [item.file.name, item.outcome, item.message])).toEqual([
      ["a.jpg", "unsupported", "格式不支持"],
      ["b.PDF", "pending", null],
      ["c.doc", "unsupported", "旧版 .doc 请先另存为 .docx"],
    ]);
    const uploaded: string[] = [];
    await run(
      items,
      async (file) => void uploaded.push(file.name),
      () => {},
    );
    expect(uploaded).toEqual(["b.PDF"]);
  });

  it("按后端错误码归类：已在库里算重复，格式不对算不支持，其余算失败", () => {
    expect(classify(new ApiError(409, "已经在知识库里了", "KNOWLEDGE_CONFLICT"))).toEqual({
      outcome: "duplicate",
      message: "知识库里已有这份文件",
    });
    expect(classify(new ApiError(415, "格式不支持", "KNOWLEDGE_UNSUPPORTED_TYPE")).outcome).toBe("unsupported");
    expect(classify(new ApiError(503, "模型服务暂不可用", "AI_UNAVAILABLE"))).toEqual({
      outcome: "failed",
      message: "模型服务暂不可用",
    });
  });
});
