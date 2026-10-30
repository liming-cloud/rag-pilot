import type { ReactNode } from "react";
import { createBrowserRouter, Navigate, type RouteObject } from "react-router";
import { AnswersPage } from "@/features/answers/AnswersPage";
import { ChangePasswordPage } from "@/features/auth/ChangePasswordPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { ChatPage } from "@/features/chat/ChatPage";
import { KnowledgePage } from "@/features/knowledge/KnowledgePage";
import { RolesPage } from "@/features/roles/RolesPage";
import { StatsPage } from "@/features/stats/StatsPage";
import { UsersPage } from "@/features/users/UsersPage";
import { getToken } from "@/lib/auth";
import { AdminLayout } from "./AdminLayout";
import { AppShell, RequirePasswordChanged, RequirePermission } from "./AppShell";

/** 没有令牌时先去登录；令牌过期由 authFetch 收到 401 时处理。管理员重置过密码时先去改密码。 */
function RequireAuth({ children }: { children: ReactNode }) {
  return getToken() ? <RequirePasswordChanged>{children}</RequirePasswordChanged> : <Navigate to="/login" replace />;
}

const guarded = (permission: string, page: ReactNode) => (
  <RequirePermission permission={permission}>{page}</RequirePermission>
);

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
      { path: "/chat", element: guarded("chat:use", <ChatPage />) },
      { path: "/chat/:conversationId", element: guarded("chat:use", <ChatPage />) },
      { path: "/change-password", element: <ChangePasswordPage /> },
    ],
  },
  {
    path: "/admin",
    element: (
      <RequireAuth>
        <AdminLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: guarded("kb:view", <KnowledgePage />) },
      { path: "knowledge", element: guarded("kb:view", <KnowledgePage />) },
      { path: "users", element: guarded("user:view", <UsersPage />) },
      { path: "roles", element: guarded("role:view", <RolesPage />) },
      { path: "answers", element: guarded("answer:view", <AnswersPage />) },
      { path: "stats", element: guarded("stats:view", <StatsPage />) },
    ],
  },
];

export const router = createBrowserRouter(routes);
