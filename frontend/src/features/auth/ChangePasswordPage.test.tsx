import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { signInAs } from "@/mocks/identity";
import { ChangePasswordPage } from "./ChangePasswordPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: "/change-password", element: <ChangePasswordPage /> },
      { path: "/chat", element: <p>问答页</p> },
    ],
    { initialEntries: ["/change-password"] },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

function fill(oldPassword: string, newPassword: string, confirm = newPassword) {
  fireEvent.change(screen.getByLabelText(/临时密码|当前密码/), { target: { value: oldPassword } });
  fireEvent.change(screen.getByLabelText(/新密码（/), { target: { value: newPassword } });
  fireEvent.change(screen.getByLabelText("再输一次新密码"), { target: { value: confirm } });
  fireEvent.click(screen.getByRole("button", { name: "保存新密码" }));
}

describe("修改密码", () => {
  it("新密码太短、与用户名相同、两次不一致时提示", async () => {
    renderPage();
    await screen.findByLabelText("当前密码");

    fill("old-password", "short");
    expect(screen.getByRole("alert")).toHaveTextContent("新密码要 8 到 64 位");
    fill("old-password", "staff01");
    expect(screen.getByRole("alert")).toHaveTextContent("8 到 64 位");
    fill("old-password", "new-password-1", "new-password-2");
    expect(screen.getByRole("alert")).toHaveTextContent("两次输入的新密码不一致");
  });

  it("被重置过密码的员工改完后回到问答页", async () => {
    signInAs("reset01");
    const router = renderPage();
    await screen.findByLabelText("管理员给你的临时密码");

    fill("temp-pass-1", "new-password-1");

    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/chat"));
  });
});
