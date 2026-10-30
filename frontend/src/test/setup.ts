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
    reader.onerror = () => reject(reader.error);
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
  return converted;
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
  server.listen({ onUnhandledRequest: "error" });
  // MSW 在 listen 时接管 fetch 并自己构造请求，FormData 的转换要套在它外面。
  const mswFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) =>
    mswFetch(input, init?.body instanceof FormData ? { ...init, body: await toNodeFormData(init.body) } : init);
});
afterEach(() => {
  server.resetHandlers();
  // 假接口记住的登录账号存在 sessionStorage（见 mocks/identity.ts），每个测试之后清掉。
  sessionStorage.clear();
  resetMockData();
  cleanup();
});
afterAll(() => server.close());
