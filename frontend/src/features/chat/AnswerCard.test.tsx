import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/mocks/server";
import { AnswerCard } from "./AnswerCard";
import type { Answer, Citation } from "./api";

function citation(index: number, change: Partial<Citation>): Citation {
  return {
    index,
    chunkId: 100 + index,
    documentId: 10 + index,
    documentName: `资料${index}.md`,
    headingPath: "章节",
    excerpt: "原文",
    documentUpdatedAt: "2026-10-26T09:00:00+08:00",
    documentState: "CURRENT",
    ...change,
  };
}

const answered: Answer = {
  id: 701,
  text: "先除霜[1]，再记温度[2]，最后交班[3]。",
  refused: false,
  refusalReason: null,
  citations: [
    citation(1, {}),
    citation(2, { documentState: "EXPIRED", documentUpdatedAt: "2025-12-01T09:00:00+08:00" }),
    citation(3, { documentState: "REPLACED" }),
  ],
  topRelevance: 0.9,
};

describe("回答卡片（#145 A7、A8、A11）", () => {
  it("来源带资料的更新日期，过了失效日期、已有新版本的标出来", () => {
    render(<AnswerCard answer={answered} />);

    const sources = within(screen.getByRole("list", { name: "来源" })).getAllByRole("listitem");
    expect(sources[0]).toHaveTextContent("更新于 2026-10-26");
    expect(sources[0]).not.toHaveTextContent("已过失效日期");
    expect(sources[1]).toHaveTextContent("更新于 2025-12-01");
    expect(sources[1]).toHaveTextContent("已过失效日期");
    expect(sources[2]).toHaveTextContent("已有新版本");
  });

  it("点开引用时上报是哪条回答的第几个引用；上报失败也照常显示原文", async () => {
    const reported: string[] = [];
    server.use(
      http.post("/api/answers/:id/citations/:index/opened", ({ params }) => {
        reported.push(`${String(params.id)}#${String(params.index)}`);
        return new HttpResponse(null, { status: 500 });
      }),
    );
    render(<AnswerCard answer={answered} />);

    fireEvent.click(await screen.findByRole("button", { name: "查看引用 2" }));

    expect(within(screen.getByRole("region", { name: "引用原文" })).getByText("资料2.md")).toBeInTheDocument();
    await vi.waitFor(() => expect(reported).toEqual(["701#2"]));
  });

  it("答不上来时给出换个问法的建议和该问谁（行业包的话术）", () => {
    render(
      <AnswerCard
        answer={{ ...answered, refused: true, refusalReason: "LOW_RELEVANCE", text: "没找到依据。", citations: [] }}
        refusalHelp={{ tips: ["说出具体的商品名。", "拆成几个小问题。"], contact: "还是找不到的话，请联系店长确认。" }}
      />,
    );

    expect(within(screen.getByRole("list", { name: "换个问法" })).getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByText("还是找不到的话，请联系店长确认。")).toBeInTheDocument();
  });
});
