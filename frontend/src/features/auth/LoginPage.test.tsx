import { fireEvent, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { getToken } from "@/lib/auth";
import { LoginPage } from "./LoginPage";

function renderLogin() {
  const router = createMemoryRouter(
    [
      { path: "/login", element: <LoginPage /> },
      { path: "/chat", element: <p>问答页</p> },
    ],
    { initialEntries: ["/login"] },
  );
  render(<RouterProvider router={router} />);
}

function submit(username: string, password: string) {
  fireEvent.change(screen.getByLabelText("用户名"), { target: { value: username } });
  fireEvent.change(screen.getByLabelText("密码"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "登录" }));
}

describe("登录页", () => {
  afterEach(() => localStorage.clear());

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
});
