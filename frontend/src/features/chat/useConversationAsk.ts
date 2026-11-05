import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useNavigate } from "react-router";
import {
  askInConversation,
  chatKeys,
  type Conversation,
  type ConversationKnowledgeBase,
  createConversation,
  stopTurn,
} from "./api";

/** 正在生成的一轮。replacesTurnId 有值时是重新生成或修改问题，生成期间先不显示被替换的那一轮。 */
export type Live = {
  conversationId: number;
  question: string;
  replacesTurnId?: number;
  turnId?: number;
  status: string;
  text: string;
  failed?: boolean;
  /** 失败的原因，服务端给的说明（#145 A2）。 */
  error?: string;
};

/**
 * 在会话里提问（#145 从问答页抽出）：还没有会话时先建一个并跳过去；生成期间把处理阶段和流出的文字放在 live 里，
 * 结束后把这一轮写进会话的缓存、清掉 live，失败时 live 标成失败。切换到别的会话后，旧会话的事件不再改 live。
 *
 * @param selected 新建会话时选中的知识库
 * @param options 新建会话时用来补全知识库名称
 */
export function useConversationAsk(
  conversationId: number | null,
  selected: number[],
  options: ConversationKnowledgeBase[],
) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [live, setLive] = useState<Live | null>(null);
  /** 新建会话失败的原因；还没有会话，没有地方放这一轮（#145 A2）。 */
  const [startError, setStartError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);

  async function ask(question: string, replacesTurnId?: number) {
    let id = conversationId;
    setStartError(null);
    if (id === null) {
      let created;
      try {
        created = await createConversation(selected);
      } catch (error) {
        setStartError(error instanceof Error ? error.message : "新建会话失败，请稍后重试");
        return;
      }
      id = created.id;
      queryClient.setQueryData<Conversation>(chatKeys.conversation(id), {
        id,
        title: created.title,
        lastAskedAt: created.lastAskedAt,
        knowledgeBases: options.filter((option) => created.knowledgeBaseIds.includes(option.id)),
        turns: [],
      });
      void navigate(`/chat/${id}`);
    }
    const target = id;
    setLive({ conversationId: target, question, replacesTurnId, status: "正在准备…", text: "" });
    const update = (change: Partial<Live>) =>
      setLive((current) => (current && current.conversationId === target ? { ...current, ...change } : current));
    const controller = new AbortController();
    abort.current = controller;
    try {
      const turn = await askInConversation(
        target,
        { question, replacesTurnId },
        {
          signal: controller.signal,
          onTurn: (turnId) => update({ turnId }),
          onStatus: (status) => update({ status }),
          onDelta: (delta) =>
            setLive((current) =>
              current && current.conversationId === target ? { ...current, text: current.text + delta } : current,
            ),
        },
      );
      queryClient.setQueryData<Conversation>(chatKeys.conversation(target), (current) =>
        current
          ? { ...current, turns: [...current.turns.filter((candidate) => candidate.id !== replacesTurnId), turn] }
          : current,
      );
      setLive((current) => (current?.conversationId === target ? null : current));
      // 标题在第一轮回答后由后台生成，列表顺序也变了，重新读一次列表；这一轮的结果已写进会话缓存，不必重读详情。
      void queryClient.invalidateQueries({ queryKey: chatKeys.conversations });
    } catch (error) {
      if (controller.signal.aborted) {
        // 还没拿到轮次 id 就停了：服务端照样生成完并保存（D-036），等一下重新读会话。
        setLive((current) => (current?.conversationId === target ? null : current));
        void queryClient.invalidateQueries({ queryKey: chatKeys.conversation(target) });
        return;
      }
      update({ failed: true, error: error instanceof Error ? error.message : undefined });
    } finally {
      if (abort.current === controller) abort.current = null;
    }
  }

  /** 停止生成：拿到这一轮的 id 后请服务端停下；还没拿到时直接断开请求（#145 A5）。停止请求失败时不打扰员工。 */
  async function stop() {
    if (live?.turnId !== undefined && conversationId !== null) {
      await stopTurn(conversationId, live.turnId).catch(() => undefined);
    } else {
      abort.current?.abort();
    }
  }

  const visibleLive = live && live.conversationId === conversationId ? live : null;
  return {
    /** 当前会话里正在生成的一轮；别的会话的不算。 */
    live: visibleLive,
    asking: visibleLive !== null && !visibleLive.failed,
    startError,
    ask,
    stop,
  };
}
