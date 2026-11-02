import { ArrowLeft, ChevronRight, LogOut } from "lucide-react";
import { useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { Button } from "@/components/ui/button";
import { useMe } from "@/lib/permissions";
import { APP_VERSION } from "@/lib/version";
import { cn } from "@/lib/utils";
import { type AdminMenu, adminMenusFor, useSignOut } from "./AppShell";

/**
 * 管理端布局（D-038）：左侧树形菜单，只显示有菜单权限的项，一个都没有的分组整组隐藏；分组可以折叠，折叠状态记在
 * 本机。当前页面所在的分组始终展开。底部显示当前账号、回到问答、退出和运行的版本（D-048）。
 */
export function AdminLayout() {
  const me = useMe();
  const signOut = useSignOut();
  const menus = adminMenusFor(me.data);

  return (
    <div className="flex h-dvh print:block print:h-auto">
      <aside className="flex w-52 shrink-0 flex-col border-r bg-card print:hidden">
        <div className="flex h-12 items-center px-4 font-medium">管理后台</div>
        <nav aria-label="管理菜单" className="flex flex-1 flex-col gap-0.5 overflow-y-auto px-2">
          {menuTree(menus).map((node) =>
            node.group === null ? (
              <MenuLink key={node.menu.to} menu={node.menu} />
            ) : (
              <MenuGroup key={node.group} name={node.group} menus={node.menus} />
            ),
          )}
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
          <span className="px-3 text-xs text-muted-foreground">版本 {APP_VERSION}</span>
        </div>
      </aside>
      <div className="min-h-0 flex-1 overflow-y-auto print:overflow-visible">
        <Outlet />
      </div>
    </div>
  );
}

type MenuNode = { group: null; menu: AdminMenu } | { group: string; menus: AdminMenu[] };

/** 按 ADMIN_MENUS 的顺序把菜单排成树：不分组的单独一项，同组的归到一起。 */
function menuTree(menus: AdminMenu[]): MenuNode[] {
  const nodes: MenuNode[] = [];
  for (const menu of menus) {
    const last = nodes.at(-1);
    if (menu.group === null) {
      nodes.push({ group: null, menu });
    } else if (last && last.group !== null && last.group === menu.group) {
      last.menus.push(menu);
    } else {
      nodes.push({ group: menu.group, menus: [menu] });
    }
  }
  return nodes;
}

function MenuLink({ menu, nested = false }: { menu: AdminMenu; nested?: boolean }) {
  return (
    <NavLink
      to={menu.to}
      className={({ isActive }) =>
        cn(
          "rounded-md px-3 py-2 text-sm hover:bg-secondary",
          nested && "pl-8",
          isActive && "bg-accent text-accent-foreground",
        )
      }
    >
      {menu.label}
    </NavLink>
  );
}

const COLLAPSED_KEY = "admin.menu.collapsed";

function readCollapsed(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? "[]");
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function MenuGroup({ name, menus }: { name: string; menus: AdminMenu[] }) {
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(() => readCollapsed().includes(name));
  const current = menus.some((menu) => pathname.startsWith(menu.to));
  const open = current || !collapsed;

  function toggle() {
    const next = !collapsed;
    setCollapsed(next);
    const others = readCollapsed().filter((item) => item !== name);
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify(next ? [...others, name] : others));
  }

  return (
    <div role="group" aria-label={name} className="mt-2 flex flex-col gap-0.5">
      <Button
        variant="ghost"
        size="sm"
        aria-expanded={open}
        disabled={current}
        onClick={toggle}
        className="justify-start px-3 text-xs font-normal text-muted-foreground disabled:opacity-100"
      >
        <ChevronRight aria-hidden="true" className={cn("size-3.5 transition-transform", open && "rotate-90")} />
        {name}
      </Button>
      {open && menus.map((menu) => <MenuLink key={menu.to} menu={menu} nested />)}
    </div>
  );
}
