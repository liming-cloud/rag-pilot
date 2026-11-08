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
  dataCitations: [],
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

  it("数据出处和资料并列：按编号排在来源里，点 [n] 看来源、查询条件、数据截至和查询时间（#182）", async () => {
    const withData: Answer = {
      ...answered,
      text: "每天盘点两次[1]，现在还剩 24 瓶[2]。",
      citations: [citation(1, {})],
      dataCitations: [
        {
          index: 2,
          tool: "get_store_inventory",
          system: "ERP",
          dataset: "store_inventory",
          conditions: "store_id=S001，sku=A1001",
          asOf: "2026-11-08T09:30:00+08:00",
          queriedAt: "2026-11-08T09:30:02+08:00",
          truncated: true,
        },
      ],
    };
    render(<AnswerCard answer={withData} />);

    const sources = within(screen.getByRole("list", { name: "来源" })).getAllByRole("listitem");
    expect(sources).toHaveLength(2);
    expect(sources[0]).toHaveTextContent("[1] 资料1.md");
    expect(sources[1]).toHaveTextContent("[2] 数据 · ERP / store_inventory");
    expect(sources[1]).toHaveTextContent("数据截至");
    expect(sources[1]).toHaveTextContent("只列出了一部分");

    fireEvent.click(await screen.findByRole("button", { name: "查看数据出处 2" }));

    const detail = screen.getByRole("region", { name: "数据出处" });
    expect(detail).toHaveTextContent("ERP / store_inventory");
    expect(detail).toHaveTextContent("查询条件store_id=S001，sku=A1001");
    expect(detail).toHaveTextContent("数据截至");
    expect(detail).toHaveTextContent("查询时间");
    fireEvent.click(screen.getByRole("button", { name: "收起数据出处" }));
    expect(screen.queryByRole("region", { name: "数据出处" })).not.toBeInTheDocument();
  });

  it("要查的数据无权查看或暂时查不到：如实说明，不给“换个问法”的建议（#182）", () => {
    const denied: Answer = {
      ...answered,
      text: "无权查看所请求的站点的数据",
      refused: true,
      refusalReason: "DATA_DENIED",
      citations: [],
    };
    const help = { tips: ["说出具体的商品名。"], contact: "还是找不到的话，请联系店长确认。" };
    const { unmount } = render(<AnswerCard answer={denied} refusalHelp={help} />);

    expect(screen.getByRole("heading", { name: "无权查看这些数据" })).toBeInTheDocument();
    expect(screen.getByText("无权查看所请求的站点的数据")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "换个问法" })).not.toBeInTheDocument();
    unmount();

    render(<AnswerCard answer={{ ...denied, refusalReason: "DATA_UNAVAILABLE", text: "暂时查不到。" }} />);
    expect(screen.getByRole("heading", { name: "暂时查不到数据" })).toBeInTheDocument();
  });
});
