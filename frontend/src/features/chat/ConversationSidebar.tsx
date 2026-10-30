import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, Plus, Trash2, X } from "lucide-react";
import { type FormEvent, useState } from "react";
import { NavLink, useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { type ConversationSummary, deleteConversation, fetchConversations, updateConversation } from "./api";
import { groupByDate } from "./conversationGroups";

/**
 * 左侧会话列表：按“今天、昨天、前 7 天、前 30 天”分组，可以新建、切换、重命名、删除。
 * 删除只影响员工端，管理端的回答记录照常保留；会话从最后一次提问起保留 30 天（D-036）。
 */
export function ConversationSidebar({ activeId }: { activeId: number | null }) {
  const conversations = useQuery({ queryKey: ["conversations"], queryFn: fetchConversations });
  const navigate = useNavigate();

  return (
    <aside aria-label="会话" className="flex w-64 shrink-0 flex-col border-r bg-card">
      <div className="p-3">
        <Button variant="outline" className="w-full justify-start" onClick={() => navigate("/chat")}>
          <Plus aria-hidden="true" className="size-4" />
          新对话
        </Button>
      </div>
      <nav aria-label="会话列表" className="flex flex-1 flex-col gap-4 overflow-y-auto px-2 pb-3">
        {conversations.isError && <p className="px-2 text-sm text-destructive">读取会话失败</p>}
        {conversations.data?.length === 0 && <p className="px-2 text-sm text-muted-foreground">还没有会话</p>}
        {groupByDate(conversations.data ?? []).map((group) => (
          <section key={group.label} aria-label={group.label} className="flex flex-col gap-0.5">
            <h2 className="px-2 pb-1 text-xs text-muted-foreground">{group.label}</h2>
            {group.items.map((conversation) => (
              <ConversationItem
                key={conversation.id}
                conversation={conversation}
                active={conversation.id === activeId}
              />
            ))}
          </section>
        ))}
      </nav>
      <p className="border-t px-3 py-2 text-xs text-muted-foreground">会话从最后一次提问起保留 30 天</p>
    </aside>
  );
}

function ConversationItem({ conversation, active }: { conversation: ConversationSummary; active: boolean }) {
  const [mode, setMode] = useState<"view" | "rename" | "confirmDelete">("view");
  const [title, setTitle] = useState(conversation.title);
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const rename = useMutation({
    mutationFn: (next: string) => updateConversation(conversation.id, { title: next }),
    onSuccess: () => {
      setMode("view");
      void queryClient.invalidateQueries({ queryKey: ["conversations"] });
      void queryClient.invalidateQueries({ queryKey: ["conversation", conversation.id] });
    },
  });
  const remove = useMutation({
    mutationFn: () => deleteConversation(conversation.id),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ["conversation", conversation.id] });
      void queryClient.invalidateQueries({ queryKey: ["conversations"] });
      if (active) navigate("/chat");
    },
  });

  function submitRename(event: FormEvent) {
    event.preventDefault();
    const next = title.trim();
    if (!next || next === conversation.title) {
      setMode("view");
      return;
    }
    rename.mutate(next);
  }

  if (mode === "rename") {
    return (
      <form onSubmit={submitRename} className="flex items-center gap-1 px-1">
        <Label htmlFor={`title-${conversation.id}`} className="sr-only">
          会话标题
        </Label>
        <Input
          id={`title-${conversation.id}`}
          autoFocus
          value={title}
          maxLength={40}
          onChange={(event) => setTitle(event.target.value)}
          onKeyDown={(event) => event.key === "Escape" && setMode("view")}
          className="h-8"
        />
        <Button type="submit" variant="ghost" size="icon" aria-label="保存标题" disabled={rename.isPending}>
          <Check aria-hidden="true" className="size-4" />
        </Button>
      </form>
    );
  }

  if (mode === "confirmDelete") {
    return (
      <div
        role="group"
        aria-label={`删除“${conversation.title}”`}
        className="flex items-center gap-1 rounded-md bg-secondary px-2 py-1 text-sm"
      >
        <span className="flex-1 truncate">删除这个会话？</span>
        <Button variant="destructive" size="sm" onClick={() => remove.mutate()} disabled={remove.isPending}>
          删除
        </Button>
        <Button variant="ghost" size="icon" aria-label="取消删除" onClick={() => setMode("view")}>
          <X aria-hidden="true" className="size-4" />
        </Button>
      </div>
    );
  }

  return (
    <div
      className={cn(
        "group flex items-center rounded-md hover:bg-secondary",
        active && "bg-accent text-accent-foreground",
      )}
    >
      <NavLink
        to={`/chat/${conversation.id}`}
        aria-current={active ? "page" : undefined}
        className="min-w-0 flex-1 truncate px-2 py-1.5 text-sm"
      >
        {conversation.title}
      </NavLink>
      <div className="flex opacity-0 group-focus-within:opacity-100 group-hover:opacity-100">
        <Button
          variant="ghost"
          size="icon"
          className="size-7"
          aria-label={`重命名“${conversation.title}”`}
          onClick={() => {
            setTitle(conversation.title);
            setMode("rename");
          }}
        >
          <Pencil aria-hidden="true" className="size-3.5" />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className="size-7"
          aria-label={`删除“${conversation.title}”`}
          onClick={() => setMode("confirmDelete")}
        >
          <Trash2 aria-hidden="true" className="size-3.5" />
        </Button>
      </div>
    </div>
  );
}
