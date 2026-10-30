import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUp, Loader2, Pencil, RotateCcw, Square, X } from "lucide-react";
import { type FormEvent, type ReactNode, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { fetchKnowledgeBases } from "@/features/knowledge/api";
import {
  askInConversation,
  type Conversation,
  type ConversationKnowledgeBase,
  createConversation,
  fetchConversation,
  stopTurn,
  type Turn,
  updateConversation,
} from "./api";
import { AnswerCard } from "./AnswerCard";
import { ConversationSidebar } from "./ConversationSidebar";
import { KnowledgeBasePicker } from "./KnowledgeBasePicker";

/** 正在生成的一轮。replacesTurnId 有值时是重新生成或修改问题，生成期间先不显示被替换的那一轮。 */
type Live = {
  conversationId: number;
  question: string;
  replacesTurnId?: number;
  turnId?: number;
  status: string;
  text: string;
  failed?: boolean;
};

/**
 * 员工端问答页（PC）：左侧会话列表，右侧对话。参考对话 agent 的做法（D-036）：会话存在服务端、可以持续追问；
 * 知识库按会话选；首字前显示处理中的阶段；可以停止、重新生成最后一条、修改最后一个问题后重问。
 */
export function ChatPage() {
  const { conversationId } = useParams();
  const activeId = conversationId ? Number(conversationId) : null;

  return (
    <div className="flex h-full">
      <ConversationSidebar activeId={activeId} />
      <ConversationView conversationId={activeId} />
    </div>
  );
}

function ConversationView({ conversationId }: { conversationId: number | null }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const knowledgeBases = useQuery({ queryKey: ["knowledge-bases"], queryFn: fetchKnowledgeBases });
  const conversation = useQuery({
    queryKey: ["conversation", conversationId],
    queryFn: () => fetchConversation(conversationId!),
    enabled: conversationId !== null,
  });
  const [draft, setDraft] = useState("");
  /** 正在修改的那一轮；记下所属会话，切换会话后不带过去。 */
  const [editing, setEditing] = useState<{ conversationId: number | null; turnId: number } | null>(null);
  const [live, setLive] = useState<Live | null>(null);
  /** 新对话还没建会话时的知识库选择；null 表示默认全选。 */
  const [newSelection, setNewSelection] = useState<number[] | null>(null);
  /** 哪个会话在有问答之后改过知识库，用来提示“从下一个问题起生效”。 */
  const [selectionChangedIn, setSelectionChangedIn] = useState<number | null>(null);
  const editingTurnId = editing?.conversationId === conversationId ? editing.turnId : null;

  const accessible = knowledgeBases.data ?? [];
  const options: ConversationKnowledgeBase[] = conversation.data
    ? mergeOptions(conversation.data.knowledgeBases, accessible)
    : accessible.map((knowledgeBase) => ({ ...knowledgeBase, accessible: true }));
  const selected = conversation.data
    ? conversation.data.knowledgeBases
        .filter((knowledgeBase) => knowledgeBase.accessible)
        .map((knowledgeBase) => knowledgeBase.id)
    : (newSelection ?? accessible.map((knowledgeBase) => knowledgeBase.id));
  const asking = live !== null && live.conversationId === conversationId && !live.failed;
  const visibleLive = live && live.conversationId === conversationId ? live : null;
  const turns = (conversation.data?.turns ?? []).filter((turn) => turn.id !== visibleLive?.replacesTurnId);
  const lastTurn = turns.at(-1);
  const endRef = useRef<HTMLDivElement>(null);

  // 像对话 agent 一样跟到最新的内容：切换会话、新的一轮、处理阶段变化、文字流出时滚到末尾。
  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: "end" });
  }, [conversationId, turns.length, visibleLive?.status, visibleLive?.text]);

  async function changeSelection(ids: number[]) {
    if (conversationId === null) {
      setNewSelection(ids);
      return;
    }
    await updateConversation(conversationId, { knowledgeBaseIds: ids });
    setSelectionChangedIn(conversationId);
    await queryClient.invalidateQueries({ queryKey: ["conversation", conversationId] });
    void queryClient.invalidateQueries({ queryKey: ["conversations"] });
  }

  async function ask(question: string, replacesTurnId?: number) {
    let id = conversationId;
    if (id === null) {
      const created = await createConversation(selected);
      id = created.id;
      queryClient.setQueryData<Conversation>(["conversation", id], {
        id,
        title: created.title,
        lastAskedAt: created.lastAskedAt,
        knowledgeBases: options.filter((option) => created.knowledgeBaseIds.includes(option.id)),
        turns: [],
      });
      navigate(`/chat/${id}`);
    }
    const target = id;
    setSelectionChangedIn(null);
    setLive({ conversationId: target, question, replacesTurnId, status: "正在准备…", text: "" });
    const update = (change: Partial<Live>) =>
      setLive((current) => (current && current.conversationId === target ? { ...current, ...change } : current));
    try {
      const turn = await askInConversation(
        target,
        { question, replacesTurnId },
        {
          onTurn: (turnId) => update({ turnId }),
          onStatus: (status) => update({ status }),
          onDelta: (delta) =>
            setLive((current) =>
              current && current.conversationId === target ? { ...current, text: current.text + delta } : current,
            ),
        },
      );
      queryClient.setQueryData<Conversation>(["conversation", target], (current) =>
        current
          ? { ...current, turns: [...current.turns.filter((candidate) => candidate.id !== replacesTurnId), turn] }
          : current,
      );
      setLive((current) => (current?.conversationId === target ? null : current));
      // 标题在第一轮回答后由后台生成，列表顺序也变了，重新读一次列表；这一轮的结果已写进会话缓存，不必重读详情。
      void queryClient.invalidateQueries({ queryKey: ["conversations"] });
    } catch {
      update({ failed: true });
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const question = draft.trim();
    if (!question || asking) return;
    setDraft("");
    const replaces = editingTurnId ?? undefined;
    setEditing(null);
    void ask(question, replaces);
  }

  function startEditing(turn: Turn) {
    setEditing({ conversationId, turnId: turn.id });
    setDraft(turn.question);
  }

  async function stop() {
    if (live?.turnId !== undefined && conversationId !== null) {
      await stopTurn(conversationId, live.turnId).catch(() => undefined);
    }
  }

  const empty = turns.length === 0 && !visibleLive;

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <header className="shrink-0 border-b px-6 py-3">
        <div className="mx-auto flex max-w-3xl flex-col gap-1">
          <KnowledgeBasePicker
            options={options}
            selected={selected}
            onChange={(ids) => void changeSelection(ids)}
            disabled={asking}
            note={selectionChangedIn === conversationId && turns.length > 0 ? "从下一个问题起生效" : undefined}
          />
        </div>
      </header>
      <main className="flex-1 overflow-y-auto px-6 py-4">
        <div className="mx-auto flex max-w-3xl flex-col gap-6">
          {conversation.isError && (
            <p role="alert" className="text-sm text-destructive">
              会话不存在或已删除。
            </p>
          )}
          {empty && !conversation.isError && (
            <p className="mt-24 text-center text-muted-foreground">
              问问门店规范、商品、促销，回答会标出原文出处，可以接着追问。
            </p>
          )}
          {turns.map((turn) => (
            <TurnView
              key={turn.id}
              turn={turn}
              actions={
                turn === lastTurn && !asking ? (
                  <>
                    <Button variant="ghost" size="sm" onClick={() => void ask(turn.question, turn.id)}>
                      <RotateCcw aria-hidden="true" className="size-4" />
                      重新生成
                    </Button>
                    <Button variant="ghost" size="sm" onClick={() => startEditing(turn)}>
                      <Pencil aria-hidden="true" className="size-4" />
                      修改问题
                    </Button>
                  </>
                ) : null
              }
            />
          ))}
          {visibleLive && <LiveTurnView live={visibleLive} />}
          <div ref={endRef} />
        </div>
      </main>
      <footer className="shrink-0 border-t px-6 py-3">
        <div className="mx-auto flex max-w-3xl flex-col gap-2">
          {editingTurnId !== null && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Pencil aria-hidden="true" className="size-4" />
              正在修改上一个问题，发送后重新回答
              <Button
                variant="ghost"
                size="sm"
                aria-label="取消修改"
                onClick={() => {
                  setEditing(null);
                  setDraft("");
                }}
              >
                <X aria-hidden="true" className="size-4" />
              </Button>
            </div>
          )}
          <form
            onSubmit={submit}
            className="flex items-center gap-2 rounded-xl border border-input bg-card py-1 pr-1 pl-4"
          >
            <Label htmlFor="question" className="sr-only">
              输入问题
            </Label>
            <Input
              id="question"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              className="h-11 flex-1 rounded-none border-0 bg-transparent px-0 text-base focus:ring-0"
              placeholder={turns.length > 0 ? "接着问…" : "问问门店规范、商品、促销…"}
            />
            {asking ? (
              <Button
                type="button"
                size="icon"
                className="rounded-lg"
                aria-label="停止生成"
                onClick={() => void stop()}
              >
                <Square aria-hidden="true" className="size-4 fill-current" />
              </Button>
            ) : (
              <Button type="submit" size="icon" className="rounded-lg" aria-label="发送" disabled={!draft.trim()}>
                <ArrowUp aria-hidden="true" className="size-5" />
              </Button>
            )}
          </form>
        </div>
      </footer>
    </div>
  );
}

/** 会话里选过的库按当前授权标出能否访问；当前有权限、但会话没选的库也列出来供勾选。 */
function mergeOptions(
  chosen: ConversationKnowledgeBase[],
  accessible: Array<{ id: number; name: string }>,
): ConversationKnowledgeBase[] {
  const others = accessible
    .filter((knowledgeBase) => !chosen.some((option) => option.id === knowledgeBase.id))
    .map((knowledgeBase) => ({ ...knowledgeBase, accessible: true }));
  return [...chosen, ...others];
}

function Question({ text }: { text: string }) {
  return <p className="max-w-[85%] self-end rounded-lg bg-accent px-3 py-2 text-accent-foreground">{text}</p>;
}

function TurnView({ turn, actions }: { turn: Turn; actions: ReactNode }) {
  return (
    <section aria-label={`问答：${turn.question}`} className="flex flex-col gap-2">
      <Question text={turn.question} />
      {turn.status === "FAILED" && (
        <p role="alert" className="text-sm text-destructive">
          暂时无法回答，请稍后重试。
        </p>
      )}
      {turn.status === "STOPPED" && !turn.answer && (
        <p className="text-sm text-muted-foreground">已停止生成，没有留下可核对的内容。</p>
      )}
      {turn.answer && <AnswerCard answer={turn.answer} />}
      {(turn.status === "STOPPED" && turn.answer) || actions ? (
        <div className="flex items-center gap-1">
          {turn.status === "STOPPED" && turn.answer && (
            <span className="text-xs text-muted-foreground">已停止生成</span>
          )}
          {actions}
        </div>
      ) : null}
    </section>
  );
}

function LiveTurnView({ live }: { live: Live }) {
  return (
    <section aria-label={`问答：${live.question}`} className="flex flex-col gap-2">
      <Question text={live.question} />
      {live.failed ? (
        <p role="alert" className="text-sm text-destructive">
          暂时无法回答，请稍后重试。
        </p>
      ) : live.text ? (
        <p aria-live="polite" className="rounded-lg border bg-card p-4 leading-relaxed">
          {live.text}
        </p>
      ) : (
        <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          {live.status}
        </p>
      )}
    </section>
  );
}
