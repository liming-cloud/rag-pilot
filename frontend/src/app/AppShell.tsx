import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LogOut } from "lucide-react";
import type { ReactNode } from "react";
import { Navigate, NavLink, Outlet, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { fetchMe, logout } from "@/features/auth/api";
import { cn } from "@/lib/utils";

const LINKS = [
  { to: "/chat", label: "问答", adminOnly: false },
  { to: "/admin/knowledge", label: "知识库", adminOnly: true },
  { to: "/admin/users", label: "账号", adminOnly: true },
  { to: "/admin/answers", label: "回答记录", adminOnly: true },
];

function useMe() {
  return useQuery({ queryKey: ["me"], queryFn: fetchMe, staleTime: 5 * 60 * 1000 });
}

/**
 * 登录后的页面外壳：顶部一行放导航、当前用户和退出，下面是各页面。导航按角色显示，店员只看到“问答”。
 * 顶部只占一行，问答页在手机上仍能把输入框固定在底部。
 */
export function AppShell() {
  const me = useMe();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const isAdmin = me.data?.role === "ADMIN";

  async function signOut() {
    await logout().catch(() => undefined);
    queryClient.clear();
    navigate("/login", { replace: true });
  }

  return (
    <div className="flex h-dvh flex-col">
      <header className="flex h-12 shrink-0 items-center gap-3 border-b bg-card px-3">
        <span className="hidden font-medium sm:inline">门店运营知识库</span>
        <nav aria-label="主导航" className="flex min-w-0 flex-1 gap-1 overflow-x-auto">
          {LINKS.filter((link) => !link.adminOnly || isAdmin).map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              className={({ isActive }) =>
                cn(
                  "shrink-0 rounded-md px-3 py-1.5 text-sm hover:bg-secondary",
                  isActive && "bg-accent text-accent-foreground",
                )
              }
            >
              {link.label}
            </NavLink>
          ))}
        </nav>
        {me.data && <span className="hidden text-sm text-muted-foreground sm:inline">{me.data.displayName}</span>}
        <Button variant="ghost" size="sm" aria-label="退出" onClick={() => void signOut()}>
          <LogOut aria-hidden="true" className="size-4" />
          <span className="hidden sm:inline">退出</span>
        </Button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto">
        <Outlet />
      </div>
    </div>
  );
}

/** 管理页只给管理员；店员打开时跳回问答页。读取当前用户期间先不渲染，避免闪出 403。 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const me = useMe();
  if (me.isPending) {
    return null;
  }
  return me.data?.role === "ADMIN" ? children : <Navigate to="/chat" replace />;
}
