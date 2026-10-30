import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { KnowledgeBasePicker } from "./KnowledgeBasePicker";

const options = Array.from({ length: 24 }, (_, index) => ({
  id: index + 1,
  name: index === 0 ? "门店运营知识库" : `区域 ${index} 门店规范`,
  accessible: true,
}));
const all = options.map((option) => option.id);

describe("KnowledgeBasePicker", () => {
  it("库很多时顶部只占一个按钮，写明选了几个", () => {
    const { rerender } = render(<KnowledgeBasePicker options={options} selected={all} onChange={() => {}} />);
    expect(screen.getByRole("button", { name: "全部知识库（24）" })).toBeInTheDocument();
    expect(screen.queryByText("区域 1 门店规范")).not.toBeInTheDocument();

    rerender(<KnowledgeBasePicker options={options} selected={[1, 2, 3]} onChange={() => {}} />);
    expect(screen.getByRole("button", { name: "门店运营知识库 等 3 个" })).toBeInTheDocument();
  });

  it("在弹窗里搜索、勾选，点确定才生效", () => {
    const onChange = vi.fn();
    render(<KnowledgeBasePicker options={options} selected={all} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: "全部知识库（24）" }));
    const dialog = screen.getByRole("dialog", { name: "选择知识库" });

    fireEvent.click(within(dialog).getByRole("button", { name: "全不选" }));
    fireEvent.change(within(dialog).getByLabelText("搜索知识库"), { target: { value: "区域 1" } });
    const list = within(dialog).getByRole("list", { name: "可选的知识库" });
    expect(within(list).getAllByRole("checkbox")).toHaveLength(11);
    fireEvent.click(within(list).getByRole("checkbox", { name: "区域 12 门店规范" }));
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole("button", { name: "确定" }));
    expect(onChange).toHaveBeenCalledWith([13]);
  });

  it("没有改动时点确定不触发变更", () => {
    const onChange = vi.fn();
    render(<KnowledgeBasePicker options={options} selected={all} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: "全部知识库（24）" }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
