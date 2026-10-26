import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { server } from "@/mocks/server";

// jsdom 里的 fetch 需要绝对地址，测试统一以本地地址为基准解析相对路径。
const originalFetch = globalThis.fetch;
globalThis.fetch = (input, init) =>
  originalFetch(typeof input === "string" && input.startsWith("/") ? `http://localhost${input}` : input, init);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());
