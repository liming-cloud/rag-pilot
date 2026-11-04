import { fireEvent, render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "@/mocks/server";
import { FeedbackBar } from "./FeedbackBar";

/** 记下发给后端的评价请求。 */
function recordFeedback() {
  const requests: { method: string; body: unknown }[] = [];
  server.use(
    http.put("/api/answers/:id/feedback", async ({ request }) => {
      requests.push({ method: "PUT", body: await request.json() });
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete("/api/answers/:id/feedback", () => {
      requests.push({ method: "DELETE", body: null });
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return requests;
}

describe("回答下方的评价（#145 C8）", () => {
  it("还没有回答 id 时不显示", () => {
    const { container } = render(<FeedbackBar answerId={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("点“有帮助”提交，再点一次撤销", async () => {
    const requests = recordFeedback();
    render(<FeedbackBar answerId={7} />);

    fireEvent.click(screen.getByRole("button", { name: "有帮助" }));
    expect(await screen.findByRole("button", { name: "有帮助", pressed: true })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "有帮助" }));

    expect(await screen.findByRole("button", { name: "有帮助", pressed: false })).toBeInTheDocument();
    await vi.waitFor(() =>
      expect(requests).toEqual([
        { method: "PUT", body: { rating: "UP" } },
        { method: "DELETE", body: null },
      ]),
    );
  });

  it("点“没帮助”后可以补一句哪里不对，提交后道谢；空白留言不能提交", async () => {
    const requests = recordFeedback();
    render(<FeedbackBar answerId={7} allowUp={false} />);
    expect(screen.queryByRole("button", { name: "有帮助" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "没帮助" }));
    const input = await screen.findByLabelText("哪里不对？（选填）");
    fireEvent.change(input, { target: { value: "   " } });
    expect(screen.getByRole("button", { name: "提交" })).toBeDisabled();
    fireEvent.change(input, { target: { value: " 没说哪个货架 " } });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));

    expect(await screen.findByText("已收到，谢谢反馈")).toBeInTheDocument();
    expect(screen.queryByLabelText("哪里不对？（选填）")).not.toBeInTheDocument();
    expect(requests).toEqual([
      { method: "PUT", body: { rating: "DOWN" } },
      { method: "PUT", body: { rating: "DOWN", comment: "没说哪个货架" } },
    ]);
  });

  it("提交失败时恢复原状并提示重试", async () => {
    server.use(http.put("/api/answers/:id/feedback", () => HttpResponse.json({}, { status: 500 })));
    render(<FeedbackBar answerId={7} />);

    fireEvent.click(screen.getByRole("button", { name: "没帮助" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("反馈没有提交成功");
    expect(screen.getByRole("button", { name: "没帮助", pressed: false })).toBeInTheDocument();
    expect(screen.queryByLabelText("哪里不对？（选填）")).not.toBeInTheDocument();
  });
});
