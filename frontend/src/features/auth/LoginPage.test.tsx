import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { getToken } from "@/lib/auth";
import { server } from "@/mocks/server";
import { LoginPage } from "./LoginPage";

function renderLogin() {
  const router = createMemoryRouter(
    [
      { path: "/login", element: <LoginPage /> },
      { path: "/chat", element: <p>问答页</p> },
    ],
    { initialEntries: ["/login"] },
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

function submit(username: string, password: string) {
  fireEvent.change(screen.getByLabelText("用户名"), { target: { value: username } });
  fireEvent.change(screen.getByLabelText("密码"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "登录" }));
}

describe("登录页", () => {
  afterEach(() => localStorage.clear());

  it("显示部署配置的产品名称、slogan、按钮下方提示和核心价值，浏览器标题也是产品名称", async () => {
    server.use(
      http.get("/api/auth/branding", () =>
        HttpResponse.json({ name: "门店运营知识库", tagline: "问了就有出处", loginHint: "账号由店长开通。" }),
      ),
    );
    renderLogin();

    expect(await screen.findByRole("heading", { name: "登录门店运营知识库" })).toBeInTheDocument();
    expect(screen.getByText("账号由店长开通。")).toBeInTheDocument();
    expect(screen.getAllByText("问了就有出处").length).toBeGreaterThan(0);
    const intro = screen.getByRole("region", { name: "产品介绍" });
    expect(intro).toHaveTextContent("有出处");
    expect(intro).toHaveTextContent("不越权");
    expect(screen.getByRole("img", { name: /引用编号/ })).toBeInTheDocument();
    expect(document.title).toBe("门店运营知识库");
  });

  it("读不到配置时用内核默认名称和提示，照常能登录", async () => {
    server.use(http.get("/api/auth/branding", () => new HttpResponse(null, { status: 500 })));
    renderLogin();

    expect(await screen.findByRole("heading", { name: "登录明答" })).toBeInTheDocument();
    expect(screen.getByText("账号由管理员开通；忘记密码请联系管理员。")).toBeInTheDocument();
    submit("staff01", "staff-password");
    expect(await screen.findByText("问答页")).toBeInTheDocument();
  });

  it("登录成功后保存令牌并进入问答页", async () => {
    renderLogin();
    submit("staff01", "staff-password");

    expect(await screen.findByText("问答页")).toBeInTheDocument();
    expect(getToken()).toBe("mock-token");
  });

  it("用户名或密码错误时提示", async () => {
    renderLogin();
    submit("staff01", "wrong-password");

    expect(await screen.findByText("用户名或密码错误")).toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it("账号被锁定时显示原因和要等多久", async () => {
    renderLogin();
    submit("staff02", "any-password");

    expect(await screen.findByText(/账号已锁定，请 \d+ 分钟后再试/)).toBeInTheDocument();
  });
});
