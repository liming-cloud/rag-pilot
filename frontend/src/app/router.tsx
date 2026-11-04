import type { ReactNode } from "react";
import { createBrowserRouter, Navigate, type RouteObject } from "react-router";
import { AnswersPage } from "@/features/answers/AnswersPage";
import { GapsPage } from "@/features/gaps/GapsPage";
import { ChangePasswordPage } from "@/features/auth/ChangePasswordPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { ChatPage } from "@/features/chat/ChatPage";
import { KnowledgeDetailPage } from "@/features/knowledge/KnowledgeDetailPage";
import { KnowledgePage } from "@/features/knowledge/KnowledgePage";
import { RolesPage } from "@/features/roles/RolesPage";
import { ModelsPage } from "@/features/models/ModelsPage";
import { SettingsPage } from "@/features/settings/SettingsPage";
import { StatsPage } from "@/features/stats/StatsPage";
import { UsersPage } from "@/features/users/UsersPage";
import { getToken } from "@/lib/auth";
import { AdminLayout } from "./AdminLayout";
import { AdminHome, AppShell, RequirePasswordChanged, RequirePermission } from "./AppShell";
import { type Permission, PERMISSIONS } from "@/lib/permissions";

/** 没有令牌时先去登录；令牌过期由 authFetch 收到 401 时处理。管理员重置过密码时先去改密码。 */
function RequireAuth({ children }: { children: ReactNode }) {
  return getToken() ? <RequirePasswordChanged>{children}</RequirePasswordChanged> : <Navigate to="/login" replace />;
}

const guarded = (permission: Permission, page: ReactNode) => (
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
      { path: "knowledge", element: guarded(PERMISSIONS.KB_VIEW, <KnowledgePage />) },
      { path: "knowledge/:knowledgeBaseId", element: guarded(PERMISSIONS.KB_VIEW, <KnowledgeDetailPage />) },
      { path: "users", element: guarded(PERMISSIONS.USER_VIEW, <UsersPage />) },
      { path: "roles", element: guarded(PERMISSIONS.ROLE_VIEW, <RolesPage />) },
      { path: "gaps", element: guarded(PERMISSIONS.ANSWER_VIEW, <GapsPage />) },
      { path: "answers", element: guarded(PERMISSIONS.ANSWER_VIEW, <AnswersPage />) },
      { path: "stats", element: guarded(PERMISSIONS.STATS_VIEW, <StatsPage />) },
      { path: "models", element: guarded(PERMISSIONS.MODEL_VIEW, <ModelsPage />) },
      { path: "settings", element: guarded(PERMISSIONS.SETTING_VIEW, <SettingsPage />) },
    ],
  },
];

export const router = createBrowserRouter(routes);
