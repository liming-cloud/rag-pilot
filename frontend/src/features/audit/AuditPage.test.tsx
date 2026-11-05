import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { signInAs } from "@/mocks/identity";
import { AuditPage } from "./AuditPage";

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuditPage />
    </QueryClientProvider>,
  );
}

const rows = async () => within(await screen.findByRole("table", { name: "审计记录" })).getAllByRole("row").slice(1);

describe("审计记录（#145 C2）", () => {
  beforeEach(() => signInAs("admin"));

  it("默认看近 30 天，新的在前；操作类型和对象显示中文，操作人账号已删除时注明", async () => {
    renderPage();

    const list = await rows();
    expect(list).toHaveLength(6);
    expect(list[0]).toHaveTextContent("知识库授权给账号");
    expect(list[0]).toHaveTextContent("系统管理员");
    expect(list[0]).toHaveTextContent("知识库 #1");
    expect(list.at(-1)).toHaveTextContent("已删除的账号 #9");
  });

  it("按操作类型和时间范围筛选；没有记录时说明", async () => {
    renderPage();
    await rows();

    fireEvent.change(screen.getByLabelText("操作类型"), { target: { value: "ROLE_UPDATED" } });
    await within(await screen.findByRole("table", { name: "审计记录" })).findByText("修改角色“店长”的权限点");
    expect(await rows()).toHaveLength(1);

    fireEvent.change(screen.getByLabelText("操作类型"), { target: { value: "ACCOUNTS_IMPORTED" } });
    expect(await screen.findByText("这段时间没有这类操作。")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("时间范围"), { target: { value: "90" } });
    expect(await screen.findByText("批量导入 12 个账号")).toBeInTheDocument();
  });

  it("没有 audit:view 时显示没有权限", async () => {
    signInAs("staff01");
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent("没有权限");
  });
});
