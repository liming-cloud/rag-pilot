import type { ReactNode } from "react";
import { createBrowserRouter, Navigate, type RouteObject } from "react-router";
import { AnswersPage } from "@/features/answers/AnswersPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { ChatPage } from "@/features/chat/ChatPage";
import { KnowledgePage } from "@/features/knowledge/KnowledgePage";
import { UsersPage } from "@/features/users/UsersPage";
import { getToken } from "@/lib/auth";
import { AppShell, RequireAdmin } from "./AppShell";

/** 没有令牌时先去登录；令牌过期由 authFetch 收到 401 时处理。 */
function RequireAuth({ children }: { children: ReactNode }) {
  return getToken() ? children : <Navigate to="/login" replace />;
}

const admin = (page: ReactNode) => <RequireAdmin>{page}</RequireAdmin>;

/** 路由表单独导出，测试用内存路由复用同一份配置。 */
export const routes: RouteObject[] = [
  { path: "/", element: <Navigate to="/chat" replace /> },
  { path: "/login", element: <LoginPage /> },
  {
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { path: "/chat", element: <ChatPage /> },
      { path: "/admin/knowledge", element: admin(<KnowledgePage />) },
      { path: "/admin/users", element: admin(<UsersPage />) },
      { path: "/admin/answers", element: admin(<AnswersPage />) },
    ],
  },
];

export const router = createBrowserRouter(routes);
