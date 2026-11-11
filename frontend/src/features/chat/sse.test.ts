import { describe, expect, it } from "vitest";
import { sseParser } from "./sse";

/** 把各块依次喂给解析器，返回解析出的全部事件。 */
function parse(...chunks: string[]) {
  const push = sseParser();
  return chunks.flatMap((chunk) => push(chunk));
}

describe("SSE 解析（按 HTML 标准的事件流格式）", () => {
  it("一个事件：event 和 data，空行结束", () => {
    expect(parse('event:delta\ndata:{"text":"你好"}\n\n')).toEqual([{ name: "delta", data: '{"text":"你好"}' }]);
  });

  it("多行 data 用换行拼接", () => {
    expect(parse("event:done\ndata:第一行\ndata:第二行\n\n")).toEqual([{ name: "done", data: "第一行\n第二行" }]);
  });

  it("兼容 \\r\\n 和单独的 \\r 换行", () => {
    expect(parse("event:a\r\ndata:1\r\n\r\nevent:b\rdata:2\r\r\n")).toEqual([
      { name: "a", data: "1" },
      { name: "b", data: "2" },
    ]);
  });

  it("分块边界落在事件中间、行中间、\\r\\n 中间", () => {
    expect(parse("eve", "nt:delta\nda", "ta:{}\r", "\n\r", "\n")).toEqual([{ name: "delta", data: "{}" }]);
  });

  it("冒号开头的注释行忽略；冒号后的一个空格去掉", () => {
    expect(parse(": keep-alive\n\nevent: status\ndata: 正在检索\n\n")).toEqual([{ name: "status", data: "正在检索" }]);
  });

  it("没有 event 时事件名是 message；没有 data 的事件不派发", () => {
    expect(parse("data:x\n\nevent:empty\n\n")).toEqual([{ name: "message", data: "x" }]);
  });

  it("最后一个事件还没收到空行时先不派发", () => {
    const push = sseParser();
    expect(push("event:done\ndata:{}\n")).toEqual([]);
    expect(push("\n")).toEqual([{ name: "done", data: "{}" }]);
  });
});
