import type { ComponentType, ReactNode } from "react";
import { createBrowserRouter, Navigate, type RouteObject, useLocation } from "react-router";
import { ChangePasswordPage } from "@/features/auth/ChangePasswordPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { ChatPage } from "@/features/chat/ChatPage";
import { getToken, loginPath } from "@/lib/auth";
import { type Permission, PERMISSIONS } from "@/lib/permissions";
import { AdminLayout } from "./AdminLayout";
import { AdminHome, AppShell, RequirePasswordChanged, RequirePermission } from "./AppShell";

/** 没有令牌时先去登录；令牌过期由 authFetch 收到 401 时处理。管理员重置过密码时先去改密码。 */
function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  return getToken() ? (
    <RequirePasswordChanged>{children}</RequirePasswordChanged>
  ) : (
    <Navigate to={loginPath(location.pathname + location.search)} replace />
  );
}

const guarded = (permission: Permission, page: ReactNode) => (
  <RequirePermission permission={permission}>{page}</RequirePermission>
);

/**
 * 管理端页面按路由分包，打开时才下载（#145）：只用问答页的员工不用下载管理端的代码。
 * 问答页和登录页最常用，留在主包里。
 */
const adminPage = (permission: Permission, load: () => Promise<ComponentType>): Pick<RouteObject, "lazy"> => ({
  lazy: async () => {
    const Page = await load();
    return { element: guarded(permission, <Page />) };
  },
});

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
      { path: "/chat", element: guarded(PERMISSIONS.CHAT_USE, <ChatPage />) },
      { path: "/chat/:conversationId", element: guarded(PERMISSIONS.CHAT_USE, <ChatPage />) },
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
      { index: true, element: <AdminHome /> },
      {
        path: "knowledge",
        ...adminPage(PERMISSIONS.KB_VIEW, () =>
          import("@/features/knowledge/KnowledgePage").then((m) => m.KnowledgePage),
        ),
      },
      {
        path: "knowledge/:knowledgeBaseId",
        ...adminPage(PERMISSIONS.KB_VIEW, () =>
          import("@/features/knowledge/KnowledgeDetailPage").then((m) => m.KnowledgeDetailPage),
        ),
      },
      {
        path: "users",
        ...adminPage(PERMISSIONS.USER_VIEW, () => import("@/features/users/UsersPage").then((m) => m.UsersPage)),
      },
      {
        path: "roles",
        ...adminPage(PERMISSIONS.ROLE_VIEW, () => import("@/features/roles/RolesPage").then((m) => m.RolesPage)),
      },
      {
        path: "audit",
        ...adminPage(PERMISSIONS.AUDIT_VIEW, () => import("@/features/audit/AuditPage").then((m) => m.AuditPage)),
      },
      {
        path: "data",
        ...adminPage(PERMISSIONS.DATA_VIEW, () => import("@/features/data/DataGrantsPage").then((m) => m.DataGrantsPage)),
      },
      {
        path: "gaps",
        ...adminPage(PERMISSIONS.ANSWER_VIEW, () => import("@/features/gaps/GapsPage").then((m) => m.GapsPage)),
      },
      {
        path: "answers",
        ...adminPage(PERMISSIONS.ANSWER_VIEW, () =>
          import("@/features/answers/AnswersPage").then((m) => m.AnswersPage),
        ),
      },
      {
        path: "stats",
        ...adminPage(PERMISSIONS.STATS_VIEW, () => import("@/features/stats/StatsPage").then((m) => m.StatsPage)),
      },
      {
        path: "models",
        ...adminPage(PERMISSIONS.MODEL_VIEW, () => import("@/features/models/ModelsPage").then((m) => m.ModelsPage)),
      },
      {
        path: "settings",
        ...adminPage(PERMISSIONS.SETTING_VIEW, () =>
          import("@/features/settings/SettingsPage").then((m) => m.SettingsPage),
        ),
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
