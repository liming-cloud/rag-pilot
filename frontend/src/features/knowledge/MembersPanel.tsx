import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { fetchMembers, fetchUsers, setMember } from "./api";

const ROLE_LABEL = { ADMIN: "管理员账号", STAFF: "店员账号" } as const;

/**
 * 知识库成员：只有成员能检索和问到这个库。建库的管理员自动是成员；管理员也只能问到自己是成员的库。
 */
export function MembersPanel({ knowledgeBaseId }: { knowledgeBaseId: number }) {
  const queryClient = useQueryClient();
  const membersKey = ["knowledge-bases", knowledgeBaseId, "members"];
  const users = useQuery({ queryKey: ["users"], queryFn: fetchUsers });
  const members = useQuery({ queryKey: membersKey, queryFn: () => fetchMembers(knowledgeBaseId) });
  const [choice, setChoice] = useState("");

  const change = useMutation({
    mutationFn: ({ userId, granted }: { userId: number; granted: boolean }) =>
      setMember(knowledgeBaseId, userId, granted),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: membersKey }),
  });

  const memberIds = new Set(members.data?.map((member) => member.userId));
  const memberUsers = users.data?.filter((user) => memberIds.has(user.id)) ?? [];
  const candidates = users.data?.filter((user) => !memberIds.has(user.id)) ?? [];

  function grant(event: FormEvent) {
    event.preventDefault();
    if (choice) {
      change.mutate({ userId: Number(choice), granted: true }, { onSuccess: () => setChoice("") });
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-lg border bg-card p-4">
      <h3 className="font-medium">成员</h3>
      {(users.isError || members.isError) && (
        <p className="text-sm text-destructive">{(users.error ?? members.error)?.message}</p>
      )}
      <ul aria-label="成员" className="flex flex-col divide-y">
        {memberUsers.map((user) => (
          <li key={user.id} className="flex items-center justify-between gap-3 py-2">
            <div className="flex flex-col">
              <span>{user.displayName}</span>
              <span className="text-sm text-muted-foreground">
                {user.username} · {ROLE_LABEL[user.role]}
              </span>
            </div>
            <Button
              variant="ghost"
              size="sm"
              aria-label={`撤销 ${user.displayName}`}
              disabled={change.isPending}
              onClick={() => change.mutate({ userId: user.id, granted: false })}
            >
              撤销
            </Button>
          </li>
        ))}
      </ul>
      <form onSubmit={grant} className="flex gap-2">
        <label htmlFor={`grant-${knowledgeBaseId}`} className="sr-only">
          选择账号
        </label>
        <select
          id={`grant-${knowledgeBaseId}`}
          value={choice}
          onChange={(event) => setChoice(event.target.value)}
          className="h-9 min-w-0 flex-1 rounded-md border border-input bg-card px-2 text-sm"
        >
          <option value="">选择要授权的账号</option>
          {candidates.map((user) => (
            <option key={user.id} value={user.id}>
              {user.displayName}（{user.username}）
            </option>
          ))}
        </select>
        <Button type="submit" size="sm" variant="outline" disabled={!choice || change.isPending}>
          授权
        </Button>
      </form>
      {change.isError && <p className="text-sm text-destructive">{change.error.message}</p>}
    </section>
  );
}
