import { ArrowLeft, LogOut } from "lucide-react";
import { Link, NavLink, Outlet } from "react-router";
import { Button } from "@/components/ui/button";
import { useMe } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { adminMenusFor, useSignOut } from "./AppShell";

/**
 * 管理端布局（D-038）：左侧固定菜单，只显示有菜单权限的项；顶部显示当前账号、回到问答和退出。
 */
export function AdminLayout() {
  const me = useMe();
  const signOut = useSignOut();
  const menus = adminMenusFor(me.data);

  return (
    <div className="flex h-dvh">
      <aside className="flex w-52 shrink-0 flex-col border-r bg-card">
        <div className="flex h-12 items-center px-4 font-medium">管理后台</div>
        <nav aria-label="管理菜单" className="flex flex-1 flex-col gap-0.5 px-2">
          {menus.map((menu) => (
            <NavLink
              key={menu.to}
              to={menu.to}
              className={({ isActive }) =>
                cn("rounded-md px-3 py-2 text-sm hover:bg-secondary", isActive && "bg-accent text-accent-foreground")
              }
            >
              {menu.label}
            </NavLink>
          ))}
        </nav>
        <div className="flex flex-col gap-1 border-t p-2">
          <Button asChild variant="ghost" size="sm" className="justify-start">
            <Link to="/chat">
              <ArrowLeft aria-hidden="true" className="size-4" />
              回到问答
            </Link>
          </Button>
          {me.data && (
            <span className="px-3 text-xs text-muted-foreground">
              {me.data.displayName} ·{" "}
              {me.data.superAdmin ? "超级管理员" : me.data.roles.map((role) => role.name).join("、")}
            </span>
          )}
          <Button variant="ghost" size="sm" className="justify-start" onClick={() => void signOut()}>
            <LogOut aria-hidden="true" className="size-4" />
            退出
          </Button>
        </div>
      </aside>
      <div className="min-h-0 flex-1 overflow-y-auto">
        <Outlet />
      </div>
    </div>
  );
}
