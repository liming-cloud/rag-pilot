import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import type { User } from "@/features/auth/api";
import { cn } from "@/lib/utils";
import { createUser, fetchUsers, type NewUser, setUserEnabled } from "./api";

const ROLE_LABEL = { ADMIN: "管理员账号", STAFF: "店员账号" } as const;
const inputClass = "h-9 rounded-md border border-input bg-card px-3 text-sm outline-none focus:ring-2 focus:ring-ring";
const EMPTY: NewUser = { username: "", displayName: "", password: "", role: "STAFF" };

/**
 * 账号管理：管理员开账号、停用离职员工。新账号要再到知识库页授权，才能问到对应的知识库。
 */
export function UsersPage() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ["users"], queryFn: fetchUsers });
  const [form, setForm] = useState<NewUser>(EMPTY);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["users"] });

  const create = useMutation({
    mutationFn: createUser,
    onSuccess: async () => {
      setForm(EMPTY);
      await refresh();
    },
  });
  const toggle = useMutation({
    mutationFn: (user: User) => setUserEnabled(user.id, !user.enabled),
    onSuccess: refresh,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate({ ...form, username: form.username.trim(), displayName: form.displayName.trim() });
  }

  const field = (key: keyof NewUser) => ({
    value: form[key],
    onChange: (event: { target: { value: string } }) => setForm({ ...form, [key]: event.target.value }),
  });

  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-6 p-6 md:p-8">
      <h1 className="font-serif text-2xl font-semibold">账号</h1>
      <form onSubmit={submit} className="grid gap-3 rounded-lg border bg-card p-4 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          用户名
          <input className={inputClass} autoComplete="off" required {...field("username")} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          显示名
          <input className={inputClass} required {...field("displayName")} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          初始密码
          <input className={inputClass} type="password" autoComplete="new-password" required {...field("password")} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          角色
          <select className={inputClass} {...field("role")}>
            <option value="STAFF">店员（只能问答）</option>
            <option value="ADMIN">管理员（管理知识库和账号）</option>
          </select>
        </label>
        <div className="flex items-center gap-3 sm:col-span-2">
          <Button type="submit" size="sm" disabled={create.isPending}>
            新建账号
          </Button>
          {create.isError && <p className="text-sm text-destructive">{create.error.message}</p>}
        </div>
      </form>
      {users.isError && <p className="text-destructive">{users.error.message}</p>}
      {toggle.isError && <p className="text-sm text-destructive">{toggle.error.message}</p>}
      <ul aria-label="账号" className="flex flex-col divide-y rounded-lg border bg-card">
        {users.data?.map((user) => (
          <li key={user.id} className="flex items-center justify-between gap-3 p-3">
            <div className="flex flex-col">
              <span className={cn(!user.enabled && "text-muted-foreground line-through")}>{user.displayName}</span>
              <span className="text-sm text-muted-foreground">
                {user.username} · {ROLE_LABEL[user.role]}
              </span>
            </div>
            <div className="flex items-center gap-3">
              <span className={cn("text-sm", user.enabled ? "text-success" : "text-muted-foreground")}>
                {user.enabled ? "已启用" : "已停用"}
              </span>
              <Button
                variant="outline"
                size="sm"
                aria-label={`${user.enabled ? "停用" : "启用"} ${user.displayName}`}
                disabled={toggle.isPending}
                onClick={() => toggle.mutate(user)}
              >
                {user.enabled ? "停用" : "启用"}
              </Button>
            </div>
          </li>
        ))}
      </ul>
    </main>
  );
}
