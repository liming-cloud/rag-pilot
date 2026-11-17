import { toQuestionRows } from "./csv";

describe("标准题 CSV", () => {
  it("期望文档用“|”分隔，留空表示应当拒答；命中方式留空为全部；跳过空行，行号按文件", () => {
    const text = "问题,知识库,期望文档,命中方式\n临期打几折？,门店运营知识库,a.md|b.md,任一\n\n能代收快递吗？,门店运营知识库,,\n";
    expect(toQuestionRows(text)).toEqual({
      rows: [
        { line: 2, question: "临期打几折？", knowledgeBase: "门店运营知识库", documents: ["a.md", "b.md"], match: "ANY" },
        { line: 4, question: "能代收快递吗？", knowledgeBase: "门店运营知识库", documents: [], match: "ALL" },
      ],
    });
  });

  it("表头不对、缺问题或知识库、命中方式写错时指出哪一行", () => {
    expect(toQuestionRows("问题,库\n")).toEqual({ error: "表头必须是“问题，知识库，期望文档，命中方式”，请下载模板填写" });
    expect(toQuestionRows("问题,知识库,期望文档,命中方式\n,门店运营知识库,,\n")).toEqual({ error: "第 2 行：问题和知识库都要填" });
    expect(toQuestionRows("问题,知识库,期望文档,命中方式\n问,库,a.md,部分\n")).toEqual({
      error: "第 2 行：命中方式只能填“全部”或“任一”",
    });
  });
});
