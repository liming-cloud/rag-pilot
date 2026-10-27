import type { ReactNode } from "react";
import { createBrowserRouter, Navigate } from "react-router";
import { AnswersPage } from "@/features/answers/AnswersPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { ChatPage } from "@/features/chat/ChatPage";
import { KnowledgePage } from "@/features/knowledge/KnowledgePage";
import { UsersPage } from "@/features/users/UsersPage";
import { getToken } from "@/lib/auth";

/** 没有令牌时先去登录；令牌过期由 authFetch 收到 401 时处理。 */
function RequireAuth({ children }: { children: ReactNode }) {
  return getToken() ? children : <Navigate to="/login" replace />;
}

export const router = createBrowserRouter([
  { path: "/", element: <Navigate to="/chat" replace /> },
  { path: "/login", element: <LoginPage /> },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    ),
  },
  {
    path: "/admin/knowledge",
    element: (
      <RequireAuth>
        <KnowledgePage />
      </RequireAuth>
    ),
  },
  {
    path: "/admin/users",
    element: (
      <RequireAuth>
        <UsersPage />
      </RequireAuth>
    ),
  },
  {
    path: "/admin/answers",
    element: (
      <RequireAuth>
        <AnswersPage />
      </RequireAuth>
    ),
  },
]);
