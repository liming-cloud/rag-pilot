import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, RotateCcw, X } from "lucide-react";
import { type UIEvent, useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { fetchKnowledgeBases, knowledgeKeys } from "@/features/knowledge/api";
import { ErrorMessage } from "@/lib/errors";
import { ConversationSidebar } from "./ConversationSidebar";
import { KnowledgeBasePicker } from "./KnowledgeBasePicker";
import { QuestionComposer } from "./QuestionComposer";
import { TurnView, LiveTurnView } from "./Turns";
import {
  chatKeys,
  type ConversationKnowledgeBase,
  DEFAULT_CHAT_TEXTS,
  fetchChatTexts,
  fetchConversation,
  type Turn,
  updateConversation,
} from "./api";
import { useConversationAsk } from "./useConversationAsk";

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
  const knowledgeBases = useQuery({ queryKey: knowledgeKeys.all, queryFn: fetchKnowledgeBases });
  const texts =
    useQuery({ queryKey: chatKeys.texts, queryFn: fetchChatTexts, staleTime: 5 * 60 * 1000 }).data ??
    DEFAULT_CHAT_TEXTS;
  const conversation = useQuery({
    queryKey: chatKeys.conversation(conversationId),
    queryFn: () => fetchConversation(conversationId!),
    enabled: conversationId !== null,
  });
  const [draft, setDraft] = useSessionDraft(conversationId);
  /** 正在修改的那一轮；记下所属会话，切换会话后不带过去。 */
  const [editing, setEditing] = useState<{ conversationId: number | null; turnId: number } | null>(null);
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
  const {
    live: visibleLive,
    asking,
    startError,
    ask: askInThis,
    stop,
  } = useConversationAsk(conversationId, selected, options);
  const turns = (conversation.data?.turns ?? []).filter((turn) => turn.id !== visibleLive?.replacesTurnId);
  const lastTurn = turns.at(-1);
  const endRef = useRef<HTMLDivElement>(null);
  /** 员工是不是停在最底下；往上翻看时不把他拉回去（#145 A4）。 */
  const atBottom = useRef(true);

  // 跟到最新的内容：切换会话、新的一轮、处理阶段变化、文字流出时，员工本来在底部才滚到末尾。
  useEffect(() => {
    if (atBottom.current) endRef.current?.scrollIntoView?.({ block: "end" });
  }, [conversationId, turns.length, visibleLive?.status, visibleLive?.text]);

  function followScroll(event: UIEvent<HTMLElement>) {
    const element = event.currentTarget;
    atBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 40;
  }

  async function changeSelection(ids: number[]) {
    if (conversationId === null) {
      setNewSelection(ids);
      return;
    }
    await updateConversation(conversationId, { knowledgeBaseIds: ids });
    setSelectionChangedIn(conversationId);
    await queryClient.invalidateQueries({ queryKey: chatKeys.conversation(conversationId) });
    void queryClient.invalidateQueries({ queryKey: chatKeys.conversations });
  }

  /** 提问之后不再提示“改选的知识库从下一个问题起生效”；自己发的问题总要看到，滚回底部。 */
  function ask(question: string, replacesTurnId?: number) {
    setSelectionChangedIn(null);
    atBottom.current = true;
    return askInThis(question, replacesTurnId);
  }

  function submit() {
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
      <main onScroll={followScroll} className="flex-1 overflow-y-auto px-6 py-4">
        <div className="mx-auto flex max-w-3xl flex-col gap-6">
          {conversation.isError && <ErrorMessage error="会话不存在或已删除。" />}
          {empty && !conversation.isError && (
            <div className="mt-24 flex flex-col items-center gap-4">
              <p className="text-center text-muted-foreground">{texts.emptyHint}</p>
              {texts.suggestedQuestions.length > 0 && (
                <ul aria-label="推荐问题" className="grid w-full max-w-xl gap-2 sm:grid-cols-2">
                  {texts.suggestedQuestions.map((question) => (
                    <li key={question} className="flex">
                      <Button
                        variant="outline"
                        className="h-auto flex-1 justify-start py-2 text-left whitespace-normal"
                        disabled={asking}
                        onClick={() => void ask(question)}
                      >
                        {question}
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {turns.map((turn) => (
            <TurnView
              refusalHelp={{ tips: texts.refusalTips, contact: texts.refusalContact }}
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
          {visibleLive && (
            <LiveTurnView live={visibleLive} onRetry={() => void ask(visibleLive.question, visibleLive.replacesTurnId)} />
          )}
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
          <ErrorMessage error={startError} />
          <QuestionComposer
            value={draft}
            onChange={setDraft}
            onSubmit={submit}
            asking={asking}
            onStop={() => void stop()}
            placeholder={turns.length > 0 ? "接着问…" : texts.placeholder}
          />
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

/** 输入框的草稿存在 sessionStorage，按会话分开：登录过期去登录、再回来时还在（#145 A6）。关掉标签页就清掉。 */
function useSessionDraft(conversationId: number | null) {
  const key = `chat-draft:${conversationId ?? "new"}`;
  const [draft, setDraft] = useState(() => sessionStorage.getItem(key) ?? "");
  const [shownFor, setShownFor] = useState(key);
  if (shownFor !== key) {
    // 切换会话时换成那个会话的草稿（渲染期间调整状态，React 推荐的写法，不用 effect）。
    setShownFor(key);
    setDraft(sessionStorage.getItem(key) ?? "");
  }
  function update(value: string) {
    setDraft(value);
    if (value) sessionStorage.setItem(key, value);
    else sessionStorage.removeItem(key);
  }
  return [draft, update] as const;
}
