import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { fetchRoles } from "@/features/roles/api";
import { fetchUsers } from "@/features/users/api";
import { fetchMembers, fetchRoleGrants, setMember, setRoleGrant } from "./api";

/**
 * 知识库授权（D-038）：授权给账号或角色。能检索这个库的人 = 直接授权的账号 + 属于被授权角色的账号。
 * 管理员也只能问到授权给自己或自己角色的库。授权变更立即生效。
 */
export function GrantsPanel({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const queryClient = useQueryClient();
  const membersKey = ["knowledge-bases", knowledgeBaseId, "members"];
  const roleGrantsKey = ["knowledge-bases", knowledgeBaseId, "role-grants"];
  const users = useQuery({ queryKey: ["users"], queryFn: fetchUsers });
  const roles = useQuery({ queryKey: ["roles"], queryFn: fetchRoles });
  const members = useQuery({ queryKey: membersKey, queryFn: () => fetchMembers(knowledgeBaseId) });
  const roleGrants = useQuery({ queryKey: roleGrantsKey, queryFn: () => fetchRoleGrants(knowledgeBaseId) });

  const changeMember = useMutation({
    mutationFn: ({ id, granted }: { id: number; granted: boolean }) => setMember(knowledgeBaseId, id, granted),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: membersKey }),
  });
  const changeRole = useMutation({
    mutationFn: ({ id, granted }: { id: number; granted: boolean }) => setRoleGrant(knowledgeBaseId, id, granted),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: roleGrantsKey }),
  });

  const memberIds = new Set(members.data?.map((member) => member.userId));
  const roleIds = new Set(roleGrants.data?.map((grant) => grant.roleId));
  const grantableRoles = roles.data?.filter((role) => role.builtIn !== "SUPER_ADMIN") ?? [];
  const error =
    users.error ?? roles.error ?? members.error ?? roleGrants.error ?? changeMember.error ?? changeRole.error;

  return (
    <section aria-label="授权" className="flex flex-col gap-4 rounded-lg border bg-card p-4">
      <h3 className="font-medium">授权</h3>
      <p className="text-sm text-muted-foreground">能检索这个库的人 = 下面的账号 + 属于下面角色的账号。</p>
      {error && <p className="text-sm text-destructive">{error.message}</p>}
      <GrantList
        title="角色"
        granted={grantableRoles
          .filter((role) => roleIds.has(role.id))
          .map((role) => ({
            id: role.id,
            label: role.name,
            detail: `${role.userCount} 个账号`,
          }))}
        candidates={grantableRoles
          .filter((role) => !roleIds.has(role.id))
          .map((role) => ({ id: role.id, label: role.name }))}
        placeholder="选择要授权的角色"
        busy={changeRole.isPending}
        onChange={(id, granted) => changeRole.mutate({ id, granted })}
      />
      <GrantList
        title="账号"
        granted={(users.data ?? [])
          .filter((user) => memberIds.has(user.id))
          .map((user) => ({
            id: user.id,
            label: user.displayName,
            detail: `${user.username} · ${user.superAdmin ? "超级管理员" : user.roles.map((role) => role.name).join("、")}`,
          }))}
        candidates={(users.data ?? [])
          .filter((user) => !memberIds.has(user.id))
          .map((user) => ({
            id: user.id,
            label: `${user.displayName}（${user.username}）`,
          }))}
        placeholder="选择要授权的账号"
        busy={changeMember.isPending}
        onChange={(id, granted) => changeMember.mutate({ id, granted })}
      />
    </section>
  );
}

type Item = { id: number; label: string; detail?: string };

function GrantList({
  title,
  granted,
  candidates,
  placeholder,
  busy,
  onChange,
}: {
  title: string;
  granted: Item[];
  candidates: Item[];
  placeholder: string;
  busy: boolean;
  onChange: (id: number, granted: boolean) => void;
}) {
  const [choice, setChoice] = useState("");

  function grant(event: FormEvent) {
    event.preventDefault();
    if (choice) {
      onChange(Number(choice), true);
      setChoice("");
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-medium">{title}</h4>
      <ul aria-label={`已授权的${title}`} className="flex flex-col divide-y">
        {granted.length === 0 && <li className="py-2 text-sm text-muted-foreground">还没有</li>}
        {granted.map((item) => (
          <li key={item.id} className="flex items-center justify-between gap-3 py-2">
            <div className="flex flex-col">
              <span>{item.label}</span>
              {item.detail && <span className="text-sm text-muted-foreground">{item.detail}</span>}
            </div>
            <Button
              variant="ghost"
              size="sm"
              aria-label={`收回 ${item.label}`}
              disabled={busy}
              onClick={() => onChange(item.id, false)}
            >
              收回
            </Button>
          </li>
        ))}
      </ul>
      <form onSubmit={grant} className="flex gap-2">
        <Label className="sr-only" htmlFor={`grant-${title}`}>
          {placeholder}
        </Label>
        <NativeSelect
          id={`grant-${title}`}
          value={choice}
          onChange={(event) => setChoice(event.target.value)}
          className="flex-1 px-2"
        >
          <NativeSelectOption value="">{placeholder}</NativeSelectOption>
          {candidates.map((item) => (
            <NativeSelectOption key={item.id} value={item.id}>
              {item.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        <Button type="submit" size="sm" variant="outline" disabled={!choice || busy}>
          授权
        </Button>
      </form>
    </div>
  );
}
