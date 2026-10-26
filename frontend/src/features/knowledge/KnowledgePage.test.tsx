import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/mocks/server";
import { KnowledgePage } from "./KnowledgePage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <KnowledgePage />
    </QueryClientProvider>,
  );
}

describe("知识库页", () => {
  it("列出接口返回的知识库", async () => {
    renderPage();
    expect(await screen.findByText("门店运营知识库")).toBeInTheDocument();
    expect(screen.getByText("商品知识库")).toBeInTheDocument();
  });

  it("接口失败时给出中文提示", async () => {
    server.use(http.get("/api/knowledge-bases", () => new HttpResponse(null, { status: 500 })));
    renderPage();
    expect(await screen.findByText("读取知识库失败，请稍后重试。")).toBeInTheDocument();
  });
});
