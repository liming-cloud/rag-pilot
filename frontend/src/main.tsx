// 字体随前端打包，按 unicode-range 分片按需加载，客户内网也能用（D-011、D-029）；字重与页面用到的一致。
import "@fontsource/noto-sans-sc/400.css";
import "@fontsource/noto-sans-sc/500.css";
import "@fontsource/noto-sans-sc/600.css";
import "@fontsource/noto-serif-sc/600.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
import { router } from "@/app/router";
import { setUnauthorizedHandler } from "@/lib/auth";
import "./index.css";

const queryClient = new QueryClient();
setUnauthorizedHandler(() => router.navigate("/login", { replace: true }));

async function enableMocks() {
  // 只有 dev:mock 模式才加载假接口，生产构建里这段代码会被摇树去掉。
  if (import.meta.env.DEV && import.meta.env.MODE === "mock") {
    const { worker } = await import("@/mocks/browser");
    await worker.start({ onUnhandledRequest: "bypass" });
  }
}

enableMocks().then(() => {
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  );
});
