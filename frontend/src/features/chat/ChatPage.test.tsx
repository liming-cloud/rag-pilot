import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { ChatPage } from "./ChatPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ChatPage />
    </QueryClientProvider>,
  );
}

function ask(question: string) {
  fireEvent.change(screen.getByLabelText("输入问题"), { target: { value: question } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
}

describe("问答页", () => {
  it("显示带引用编号的回答和来源列表", async () => {
    renderPage();
    ask("临期商品怎么处理？");

    expect(await screen.findByText("临期商品怎么处理？")).toBeInTheDocument();
    const answer = await screen.findByRole("article", { name: "回答" });
    expect(within(answer).getByRole("button", { name: "查看引用 1" })).toBeInTheDocument();
    expect(within(answer).getByRole("list", { name: "来源" })).toHaveTextContent("临期与报损管理.md");
  });

  it("点引用编号展开原文片段", async () => {
    renderPage();
    ask("临期商品怎么处理？");

    fireEvent.click(await screen.findByRole("button", { name: "查看引用 1" }));
    const excerpt = screen.getByRole("region", { name: "引用原文" });
    expect(excerpt).toHaveTextContent("临期与报损管理.md");
    expect(excerpt).toHaveTextContent("临期商品 > 下架时限");
    expect(excerpt).toHaveTextContent("保质期剩余");
  });

  it("拒答时显示找不到答案", async () => {
    server.use(
      http.post("/api/ask", () =>
        HttpResponse.json({
          text: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
          refused: true,
          refusalReason: "LOW_RELEVANCE",
          citations: [],
          topRelevance: 0.01,
        }),
      ),
    );
    renderPage();
    ask("明天会下雨吗？");

    expect(await screen.findByText("没有找到答案")).toBeInTheDocument();
    expect(screen.getByText(/建议换个问法/)).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "来源" })).not.toBeInTheDocument();
  });

  it("接口失败时给出中文提示", async () => {
    server.use(http.post("/api/ask", () => new HttpResponse(null, { status: 503 })));
    renderPage();
    ask("临期商品怎么处理？");

    expect(await screen.findByText("暂时无法回答，请稍后重试。")).toBeInTheDocument();
  });

  it("可以点赞或点踩", async () => {
    renderPage();
    ask("临期商品怎么处理？");

    const helpful = await screen.findByRole("button", { name: "有帮助" });
    fireEvent.click(helpful);
    expect(helpful).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "没帮助" }));
    expect(helpful).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "没帮助" })).toHaveAttribute("aria-pressed", "true");
  });
});
