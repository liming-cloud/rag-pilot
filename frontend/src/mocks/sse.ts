/** 构造 SSE 响应，格式与 Spring 的 SseEmitter 一致；假接口和测试共用。 */
export function sseResponse(events: Array<[string, unknown]>, until?: Promise<void>): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    async start(controller) {
      for (const [index, [name, data]] of events.entries()) {
        // 最后一个事件之前等 until，用来在测试里观察“正在生成”的中间状态。
        if (until && index === events.length - 1) {
          await until;
        }
        controller.enqueue(encoder.encode(`event:${name}\ndata:${JSON.stringify(data)}\n\n`));
      }
      controller.close();
    },
  });
  return new Response(body, { headers: { "Content-Type": "text/event-stream" } });
}
