import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock, Plus } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { Can, useCan } from "@/lib/permissions";
import {
  createRole,
  deleteRole,
  fetchPermissionCatalog,
  fetchRoles,
  type PermissionGroup,
  type Role,
  updateRole,
} from "./api";

const BUILT_IN_LABEL = { SUPER_ADMIN: "内置 · 只读", ADMIN: "内置", STAFF: "内置" } as const;

/**
 * 角色与权限（D-038）：左边选角色，右边按菜单分组勾选权限点。菜单权限决定能不能进页面，按钮权限决定能不能做操作；
 * 勾了按钮权限会自动勾上它所在菜单。超级管理员只读；还有账号的角色和内置角色不能删。
 */
export function RolesPage() {
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const catalog = useQuery({ queryKey: ["permission-catalog"], queryFn: fetchPermissionCatalog });
  const [selectedId, setSelectedId] = useState<number | "new" | null>(null);
  const selected =
    selectedId === "new" ? null : (roles.data?.find((role) => role.id === selectedId) ?? roles.data?.[0] ?? null);
  const editing = selectedId === "new" ? "new" : selected;

  return (
    <main className="mx-auto grid max-w-5xl gap-6 p-6 md:grid-cols-[220px_1fr] md:p-8">
      <aside className="flex flex-col gap-3">
        <h1 className="font-serif text-2xl font-semibold">角色与权限</h1>
        {roles.isError && <p className="text-destructive">读取角色失败，请稍后重试。</p>}
        <nav aria-label="角色" className="flex flex-col gap-1">
          {roles.data?.map((role) => (
            <button
              key={role.id}
              type="button"
              aria-current={editing !== "new" && role.id === editing?.id}
              onClick={() => setSelectedId(role.id)}
              className={cn(
                "flex items-center justify-between rounded-md px-3 py-2 text-left hover:bg-secondary",
                editing !== "new" && role.id === editing?.id && "bg-accent text-accent-foreground",
              )}
            >
              <span>{role.name}</span>
              <span className="text-xs text-muted-foreground">{role.userCount} 人</span>
            </button>
          ))}
        </nav>
        <Can permission="role:create">
          <Button variant="outline" size="sm" onClick={() => setSelectedId("new")}>
            <Plus aria-hidden="true" className="size-4" />
            新建角色
          </Button>
        </Can>
      </aside>
      {catalog.data && editing && (
        <RoleEditor
          key={editing === "new" ? "new" : editing.id}
          role={editing === "new" ? null : editing}
          catalog={catalog.data}
          onSaved={(saved) => setSelectedId(saved.id)}
          onDeleted={() => setSelectedId(null)}
        />
      )}
    </main>
  );
}

function RoleEditor({
  role,
  catalog,
  onSaved,
  onDeleted,
}: {
  role: Role | null;
  catalog: PermissionGroup[];
  onSaved: (role: Role) => void;
  onDeleted: () => void;
}) {
  const queryClient = useQueryClient();
  const canEdit = useCan(role ? "role:update" : "role:create");
  const readOnly = role?.builtIn === "SUPER_ADMIN" || !canEdit;
  const [name, setName] = useState(role?.name ?? "");
  const [granted, setGranted] = useState<Set<string>>(new Set(role?.permissions ?? ["chat:use"]));
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["roles"] });

  const save = useMutation({
    mutationFn: () =>
      role
        ? updateRole(role.id, { name: name.trim(), permissions: [...granted] })
        : createRole({ name: name.trim(), permissions: [...granted] }),
    onSuccess: async (saved) => {
      await refresh();
      onSaved(saved);
    },
  });
  const remove = useMutation({
    mutationFn: () => deleteRole(role!.id),
    onSuccess: async () => {
      await refresh();
      onDeleted();
    },
  });

  function toggle(group: PermissionGroup, code: string, on: boolean) {
    const next = new Set(granted);
    if (on) {
      next.add(code);
      next.add(group.view.code);
    } else {
      next.delete(code);
      // 取消菜单权限时，菜单下的按钮权限一起取消。
      if (code === group.view.code) group.buttons.forEach((button) => next.delete(button.code));
    }
    setGranted(next);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (name.trim()) save.mutate();
  }

  const deletable = role && role.builtIn === null && role.userCount === 0;

  return (
    <form onSubmit={submit} aria-label="角色权限" className="flex flex-col gap-4">
      <div className="flex items-end gap-3">
        <Label className="flex flex-1 flex-col gap-1">
          角色名称
          <Input
            value={name}
            maxLength={20}
            required
            disabled={readOnly || role?.builtIn != null}
            onChange={(e) => setName(e.target.value)}
          />
        </Label>
        {role?.builtIn && (
          <span className="flex items-center gap-1 pb-2 text-sm text-muted-foreground">
            <Lock aria-hidden="true" className="size-3.5" />
            {BUILT_IN_LABEL[role.builtIn]}
          </span>
        )}
      </div>
      {role?.builtIn === "SUPER_ADMIN" && (
        <p className="text-sm text-muted-foreground">超级管理员拥有全部权限，不能修改，全系统只有一个账号。</p>
      )}
      <div className="flex flex-col gap-3">
        {catalog.map((group) => (
          <fieldset key={group.menu} className="flex flex-col gap-2 rounded-lg border bg-card p-3">
            <legend className="sr-only">{group.menu}</legend>
            <Label className="flex items-center gap-2 font-medium">
              <Checkbox
                checked={role?.builtIn === "SUPER_ADMIN" || granted.has(group.view.code)}
                disabled={readOnly}
                onCheckedChange={(checked) => toggle(group, group.view.code, checked === true)}
              />
              {group.menu}
              <span className="text-xs font-normal text-muted-foreground">{group.view.label}</span>
            </Label>
            {group.buttons.length > 0 && (
              <div className="flex flex-wrap gap-x-4 gap-y-2 pl-6">
                {group.buttons.map((button) => (
                  <Label key={button.code} className="flex items-center gap-2 text-sm font-normal">
                    <Checkbox
                      checked={role?.builtIn === "SUPER_ADMIN" || granted.has(button.code)}
                      disabled={readOnly}
                      onCheckedChange={(checked) => toggle(group, button.code, checked === true)}
                    />
                    {button.label}
                  </Label>
                ))}
              </div>
            )}
          </fieldset>
        ))}
      </div>
      {(save.isError || remove.isError) && (
        <p role="alert" className="text-sm text-destructive">
          {(save.error ?? remove.error)?.message}
        </p>
      )}
      {!readOnly && (
        <div className="flex items-center gap-3">
          <Button type="submit" size="sm" disabled={save.isPending}>
            {role ? "保存" : "新建角色"}
          </Button>
          {role && role.builtIn === null && (
            <Can permission="role:delete">
              <Button
                type="button"
                size="sm"
                variant="ghost"
                disabled={!deletable || remove.isPending}
                onClick={() => remove.mutate()}
              >
                删除角色
              </Button>
              {!deletable && (
                <span className="text-xs text-muted-foreground">
                  还有 {role.userCount} 个账号属于这个角色，先移走才能删除
                </span>
              )}
            </Can>
          )}
        </div>
      )}
    </form>
  );
}
