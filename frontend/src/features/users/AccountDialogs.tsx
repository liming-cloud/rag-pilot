import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { type Role, roleKeys } from "@/features/roles/api";
import { ErrorMessage } from "@/lib/errors";
import { type Account, assignRoles, createUser, type NewAccount, resetPassword, userKeys } from "./api";

export function RolePicker({
  roles,
  selected,
  onChange,
}: {
  roles: Role[];
  selected: number[];
  onChange: (ids: number[]) => void;
}) {
  return (
    <fieldset className="flex flex-wrap items-center gap-x-4 gap-y-2">
      <legend className="mb-1 text-sm">角色（可以多选）</legend>
      {roles.map((role) => (
        <Label key={role.id} className="flex items-center gap-2 text-sm font-normal">
          <Checkbox
            checked={selected.includes(role.id)}
            onCheckedChange={(checked) =>
              onChange(checked === true ? [...selected, role.id] : selected.filter((id) => id !== role.id))
            }
          />
          {role.name}
        </Label>
      ))}
    </fieldset>
  );
}

export function useRefreshUsers() {
  const queryClient = useQueryClient();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: userKeys.all });
    await queryClient.invalidateQueries({ queryKey: roleKeys.all });
  };
}

export function CreateDialog({ roles, onDone }: { roles: Role[]; onDone: () => void }) {
  const refresh = useRefreshUsers();
  const [form, setForm] = useState<NewAccount>({ username: "", displayName: "", password: "", roleIds: [] });
  const create = useMutation({
    mutationFn: () => createUser({ ...form, username: form.username.trim(), displayName: form.displayName.trim() }),
    onSuccess: async () => {
      await refresh();
      onDone();
    },
  });
  const field = (key: "username" | "displayName" | "password") => ({
    value: form[key],
    onChange: (event: { target: { value: string } }) => setForm({ ...form, [key]: event.target.value }),
  });

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>新建账号</DialogTitle>
        <DialogDescription>建好后把用户名和初始密码交给员工；能看哪些知识库在知识库的“授权”里设置。</DialogDescription>
      </DialogHeader>
      <form
        aria-label="新建账号"
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <Label className="flex flex-col gap-1">
          用户名
          <Input autoComplete="off" required {...field("username")} />
        </Label>
        <Label className="flex flex-col gap-1">
          显示名
          <Input required {...field("displayName")} />
        </Label>
        <Label className="flex flex-col gap-1">
          初始密码（8–64 位）
          <Input type="password" autoComplete="new-password" required {...field("password")} />
        </Label>
        <RolePicker roles={roles} selected={form.roleIds} onChange={(roleIds) => setForm({ ...form, roleIds })} />
        <ErrorMessage error={create.error} />
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={create.isPending || form.roleIds.length === 0}>
            新建账号
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}

export function RolesDialog({ account, roles, onDone }: { account: Account; roles: Role[]; onDone: () => void }) {
  const refresh = useRefreshUsers();
  const [roleIds, setRoleIds] = useState(account.roles.map((role) => role.id));
  const save = useMutation({
    mutationFn: () => assignRoles(account.id, roleIds),
    onSuccess: async () => {
      await refresh();
      onDone();
    },
  });
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>改角色：{account.displayName}</DialogTitle>
        <DialogDescription>只能分配不超过你自己权限的角色。</DialogDescription>
      </DialogHeader>
      <RolePicker roles={roles} selected={roleIds} onChange={setRoleIds} />
      <ErrorMessage error={save.error} />
      <DialogFooter>
        <Button variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        <Button size="sm" disabled={roleIds.length === 0 || save.isPending} onClick={() => save.mutate()}>
          保存角色
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}

export function PasswordDialog({ account, onDone }: { account: Account; onDone: () => void }) {
  const [password, setPassword] = useState("");
  const reset = useMutation({ mutationFn: () => resetPassword(account.id, password) });
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>重置密码：{account.displayName}</DialogTitle>
        <DialogDescription>员工用这个临时密码登录后，必须先改成自己的密码。</DialogDescription>
      </DialogHeader>
      {reset.isSuccess ? (
        <>
          <p className="text-sm">已重置。请把临时密码告诉 {account.displayName}，他下次登录时需要先改密码。</p>
          <DialogFooter>
            <Button size="sm" onClick={onDone}>
              完成
            </Button>
          </DialogFooter>
        </>
      ) : (
        <form
          className="flex flex-col gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            reset.mutate();
          }}
        >
          <Label className="flex flex-col gap-1">
            临时密码（8–64 位）
            <Input
              type="password"
              autoComplete="new-password"
              minLength={8}
              maxLength={64}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </Label>
          <ErrorMessage error={reset.error} />
          <DialogFooter>
            <Button type="button" variant="ghost" size="sm" onClick={onDone}>
              取消
            </Button>
            <Button type="submit" size="sm" disabled={reset.isPending}>
              重置
            </Button>
          </DialogFooter>
        </form>
      )}
    </DialogContent>
  );
}
