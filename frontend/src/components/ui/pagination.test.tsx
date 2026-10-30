import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Pagination, pageItems } from "./pagination";

describe("pageItems", () => {
  it("7 页以内全部显示", () => {
    expect(pageItems(3, 5)).toEqual([1, 2, 3, 4, 5]);
  });

  it("页数多时显示首末页和当前页前后，断开处是省略号", () => {
    expect(pageItems(1, 20)).toEqual([1, 2, 3, 4, 5, null, 20]);
    expect(pageItems(10, 20)).toEqual([1, null, 9, 10, 11, null, 20]);
    expect(pageItems(20, 20)).toEqual([1, null, 16, 17, 18, 19, 20]);
  });
});

describe("Pagination", () => {
  function renderAt(page: number) {
    const onChange = vi.fn();
    render(<Pagination page={page} size={20} total={385} onChange={onChange} />);
    return onChange;
  }

  it("显示总数、当前页，点页码和上一页、下一页翻页", () => {
    const onChange = renderAt(10);

    expect(screen.getByText("共 385 条")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "第 10 页" })).toHaveAttribute("aria-current", "page");
    fireEvent.click(screen.getByRole("button", { name: "第 20 页" }));
    fireEvent.click(screen.getByRole("button", { name: "上一页" }));
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));

    expect(onChange.mock.calls).toEqual([[{ page: 20, size: 20 }], [{ page: 9, size: 20 }], [{ page: 11, size: 20 }]]);
  });

  it("改每页条数回到第 1 页", () => {
    const onChange = renderAt(10);

    fireEvent.change(screen.getByLabelText("每页条数"), { target: { value: "50" } });

    expect(onChange).toHaveBeenCalledWith({ page: 1, size: 50 });
  });

  it("跳至某页，超出范围时跳到最后一页", () => {
    const onChange = renderAt(1);
    const input = screen.getByLabelText("跳转到页码");

    fireEvent.change(input, { target: { value: "7" } });
    fireEvent.submit(input);
    fireEvent.change(input, { target: { value: "999" } });
    fireEvent.submit(input);

    expect(onChange.mock.calls).toEqual([[{ page: 7, size: 20 }], [{ page: 20, size: 20 }]]);
  });

  it("第一页和最后一页时对应的翻页按钮不可用", () => {
    renderAt(20);
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "上一页" })).toBeEnabled();
  });
});
