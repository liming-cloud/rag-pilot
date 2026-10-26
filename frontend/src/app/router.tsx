import { createBrowserRouter, Navigate } from "react-router";
import { ChatPage } from "@/features/chat/ChatPage";
import { KnowledgePage } from "@/features/knowledge/KnowledgePage";

export const router = createBrowserRouter([
  { path: "/", element: <Navigate to="/chat" replace /> },
  { path: "/chat", element: <ChatPage /> },
  { path: "/admin/knowledge", element: <KnowledgePage /> },
]);
