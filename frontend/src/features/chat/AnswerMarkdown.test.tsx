import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AnswerMarkdown } from "./AnswerMarkdown";
import type { Citation } from "./api";

const CITATION: Citation = {
  index: 1,
  chunkId: 11,
  documentId: 3,
  documentName: "冷柜管理.md",
  headingPath: "冷柜管理 > 除霜",
  excerpt: "冷柜每周二除霜一次。",
  documentUpdatedAt: "2026-10-26T09:00:00+08:00",
  documentState: "CURRENT",
};

function renderText(text: string, onCite = vi.fn()) {
  const { container } = render(<AnswerMarkdown text={text} citations={[CITATION]} onCite={onCite} />);
  return { container, onCite };
}

describe("回答正文按 Markdown 显示（#145 A1）", () => {
  it("编号步骤、换段、表格照原样排版", () => {
    renderText("交班分三步：\n\n1. 清点备用金\n2. 签交班表\n3. 交钥匙\n\n| 项目 | 时间 |\n| --- | --- |\n| 除霜 | 每周二 |");

    expect(within(screen.getByRole("list")).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "清点备用金",
      "签交班表",
      "交钥匙",
    ]);
    expect(screen.getByRole("table")).toHaveTextContent("除霜");
    expect(screen.getByRole("columnheader", { name: "时间" })).toBeInTheDocument();
  });

  it("[n] 是可以点开原文的按钮；对不上的编号去掉", () => {
    const { onCite } = renderText("每周二除霜[1]，记录在表上[7]。");

    fireEvent.click(screen.getByRole("button", { name: "查看引用 1" }));

    expect(onCite).toHaveBeenCalledWith(CITATION);
    expect(screen.queryByText(/\[7\]/)).not.toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(1);
  });

  it("不渲染原始 HTML：资料或模型输出里的 <script>、<img onerror>、<a> 都不会变成元素", () => {
    const { container } = renderText(
      '先除霜<script>window.hacked = true</script><img src=x onerror="alert(1)"><a href="https://evil.example">点这里</a>[1]',
    );

    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect((window as unknown as { hacked?: boolean }).hacked).toBeUndefined();
  });

  it("Markdown 链接和图片只显示文字，不把员工带到外部地址", () => {
    const { container } = renderText("详见[供应商网站](https://evil.example)，![二维码](https://evil.example/qr.png)");

    expect(container.querySelector("a")).toBeNull();
    expect(container.querySelector("img")).toBeNull();
    expect(container).toHaveTextContent("详见供应商网站，二维码");
  });
});
