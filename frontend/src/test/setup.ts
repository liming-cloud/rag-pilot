import "@testing-library/jest-dom/vitest";
import { File as NodeFile } from "node:buffer";
import { cleanup } from "@testing-library/react";
import { resetMockData } from "@/mocks/handlers";
import { server } from "@/mocks/server";

// jsdom 里的 fetch 需要绝对地址，测试统一以本地地址为基准解析相对路径。
// 另外 jsdom 的 FormData 交给 Node 的 fetch 时会丢掉文件名，这里先转成 Node 自己的 FormData。
const originalFetch = globalThis.fetch;
const NodeFormData = (await new Response("", { headers: { "Content-Type": "application/x-www-form-urlencoded" } })
  .formData()
  .then((form) => form.constructor)) as typeof FormData;

/** jsdom 的 File 没有 arrayBuffer()，用它支持的 FileReader 读内容。 */
function readBytes(blob: Blob) {
  return new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error ?? new Error("读取文件失败"));
    reader.readAsArrayBuffer(blob);
  });
}

async function toNodeFormData(form: FormData) {
  const converted = new NodeFormData();
  for (const [key, value] of form.entries()) {
    if (typeof value === "string") {
      converted.append(key, value);
    } else {
      const file = new NodeFile([await readBytes(value)], value.name, { type: value.type });
      converted.append(key, file as unknown as Blob, value.name);
    }
  }
  // MSW 3 在匹配处理器时会复制请求，流式的 FormData 会提前关闭，所以先编码成完整的字节再发。
  const encoded = new Request("http://localhost", { method: "POST", body: converted });
  return { body: await encoded.arrayBuffer(), contentType: encoded.headers.get("Content-Type") ?? "" };
}

// jsdom 没有 ResizeObserver；Radix 的 Checkbox 放在表单里时要用它量尺寸，测试里给一个什么也不做的实现。
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

globalThis.fetch = (input, init) =>
  originalFetch(typeof input === "string" && input.startsWith("/") ? `http://localhost${input}` : input, init);

beforeAll(() => {
  server.listen({ onUnhandledFrame: "error" });
  // MSW 在 listen 时接管 fetch 并自己构造请求，FormData 的转换要套在它外面。
  const mswFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    if (!(init?.body instanceof FormData)) return mswFetch(input, init);
    const { body, contentType } = await toNodeFormData(init.body);
    const headers = new Headers(init.headers);
    headers.set("Content-Type", contentType);
    return mswFetch(input, { ...init, headers, body });
  };
});
afterEach(() => {
  server.resetHandlers();
  // 假接口记住的登录账号存在 sessionStorage（见 mocks/identity.ts），每个测试之后清掉。
  sessionStorage.clear();
  resetMockData();
  cleanup();
});
afterAll(() => server.close());
