/** 一个 SSE 事件；data 是原始文本，多行 data 已用换行拼好。 */
export type SseEvent = { name: string; data: string };

/**
 * SSE 解析器（按 HTML 标准的 event-stream 格式，D-067 决定手写不引依赖）：每次喂一块文本，返回这块里结束的事件。
 * 行以 \r\n、\n 或 \r 结尾，块的边界可以落在任何位置；空行结束一个事件；冒号开头的是注释；字段值开头的一个空格去掉；
 * 多行 data 用换行拼接；没有 event 时事件名是 message；没有 data 的事件不派发。
 */
export function sseParser(): (chunk: string) => SseEvent[] {
  let buffer = "";
  let name = "";
  let data: string[] = [];

  const dispatch = (): SseEvent[] => {
    const event = data.length > 0 ? [{ name: name || "message", data: data.join("\n") }] : [];
    name = "";
    data = [];
    return event;
  };

  const line = (text: string): SseEvent[] => {
    if (text === "") return dispatch();
    if (text.startsWith(":")) return [];
    const colon = text.indexOf(":");
    const field = colon < 0 ? text : text.slice(0, colon);
    const value = colon < 0 ? "" : text.slice(colon + 1).replace(/^ /, "");
    if (field === "event") name = value;
    else if (field === "data") data.push(value);
    return [];
  };

  return (chunk) => {
    buffer += chunk;
    const events: SseEvent[] = [];
    for (let end = buffer.search(/[\r\n]/); end >= 0; end = buffer.search(/[\r\n]/)) {
      // 块末尾的 \r 可能是 \r\n 的前半，等下一块再定。
      if (buffer[end] === "\r" && end === buffer.length - 1) break;
      const width = buffer.startsWith("\r\n", end) ? 2 : 1;
      events.push(...line(buffer.slice(0, end)));
      buffer = buffer.slice(end + width);
    }
    return events;
  };
}
