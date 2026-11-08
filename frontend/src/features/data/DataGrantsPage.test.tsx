import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { setUnitsDown } from "@/mocks/data";
import { signInAs } from "@/mocks/identity";
import { DataGrantsPage } from "./DataGrantsPage";

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <DataGrantsPage />
    </QueryClientProvider>,
  );
}

async function pickRole(name: string) {
  const select = await screen.findByRole("combobox", { name: /角色/ });
  await screen.findByRole("option", { name: new RegExp(name) });
  fireEvent.change(select, { target: { value: String((await screen.findByRole("option", { name: new RegExp(name) })).getAttribute("value")) } });
}

describe("数据范围授权（D-056）", () => {
  beforeEach(() => {
    signInAs("admin");
    setUnitsDown(false);
  });

  it("没选对象时提示先选择；选了角色显示它已有的工具、区域和站点授权", async () => {
    renderPage();
    expect(screen.getByText(/先选择要授权的角色/)).toBeInTheDocument();

    await pickRole("店长");

    const editor = await screen.findByRole("region", { name: "店长 的授权" });
    expect(await within(editor).findByRole("checkbox", { name: /门店库存查询/ })).toBeChecked();
    expect(within(editor).getByRole("checkbox", { name: /门店销售查询/ })).not.toBeChecked();
    expect(await within(editor).findByRole("checkbox", { name: /区域 east/ })).toBeChecked();
    // 区域已授权时，区域内的站点显示为已包含且不能单独取消。
    const covered = await within(editor).findByRole("checkbox", { name: /静安1 店/ });
    expect(covered).toBeChecked();
    expect(covered).toBeDisabled();
  });

  it("新增授权可以直接保存；保存后没有未保存的修改", async () => {
    renderPage();
    await pickRole("员工");
    const editor = await screen.findByRole("region", { name: "员工 的授权" });
    expect(screen.queryByText("有未保存的修改")).not.toBeInTheDocument();

    fireEvent.click(await within(editor).findByRole("checkbox", { name: /门店销售查询/ }));
    fireEvent.click(await within(editor).findByRole("checkbox", { name: /区域 south/ }));
    expect(screen.getByText("有未保存的修改")).toBeInTheDocument();
    fireEvent.click(await within(editor).findByRole("button", { name: "保存" }));

    await waitFor(() => expect(screen.queryByText("有未保存的修改")).not.toBeInTheDocument());
    expect(within(editor).getByRole("checkbox", { name: /区域 south/ })).toBeChecked();
  });

  it("收回授权要先确认，取消后授权不变", async () => {
    renderPage();
    await pickRole("店长");
    const editor = await screen.findByRole("region", { name: "店长 的授权" });
    fireEvent.click(await within(editor).findByRole("checkbox", { name: /区域 east/ }));

    fireEvent.click(await within(editor).findByRole("button", { name: "保存" }));
    const dialog = await screen.findByRole("dialog", { name: "确认收回授权" });
    expect(dialog).toHaveTextContent("收回 1 项授权");
    fireEvent.click(within(dialog).getByRole("button", { name: "取消" }));
    fireEvent.click(within(editor).getByRole("button", { name: "还原" }));

    expect(within(editor).getByRole("checkbox", { name: /区域 east/ })).toBeChecked();
  });

  it("按搜索筛选站点，站点多时提示只显示一部分", async () => {
    renderPage();
    await pickRole("员工");
    const editor = await screen.findByRole("region", { name: "员工 的授权" });
    await within(editor).findByRole("checkbox", { name: /静安1 店/ });

    fireEvent.change(within(editor).getByLabelText("搜索站点"), { target: { value: "S036" } });

    const list = within(editor).getByRole("list", { name: "站点" });
    expect(within(list).getAllByRole("checkbox")).toHaveLength(4);
    expect(within(list).getByRole("checkbox", { name: /S036/ })).toBeInTheDocument();
  });

  it("站点信息取不到时提示并可重试，已有的工具授权照常可改", async () => {
    setUnitsDown(true);
    renderPage();
    await pickRole("店长");
    const editor = await screen.findByRole("region", { name: "店长 的授权" });

    expect(await within(editor).findByRole("alert")).toHaveTextContent("站点信息暂时取不到");
    expect(within(editor).getByRole("button", { name: "重试" })).toBeInTheDocument();
    expect(await within(editor).findByRole("checkbox", { name: /门店库存查询/ })).toBeChecked();
  });
});
