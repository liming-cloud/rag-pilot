import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig(({ mode }) => ({
  // MSW 的 Service Worker 只在 mock 模式下作为静态文件提供，不进入生产构建。
  publicDir: mode === "mock" ? "mock-public" : "public",
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "src") },
  },
  server: {
    // 开发时把接口转发到本机后端（API_PROXY 可改地址，默认 8080）；用 npm run dev:mock 时由 MSW 假接口应答。
    proxy: { "/api": process.env.API_PROXY ?? "http://127.0.0.1:8080" },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
}));
