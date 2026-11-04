import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
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

  async function ask(question: string, replacesTurnId?: number) {
    let id = conversationId;
    if (id === null) {
      const created = await createConversation(selected);
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
      queryClient.setQueryData<Conversation>(chatKeys.conversation(target), (current) =>
        current
          ? { ...current, turns: [...current.turns.filter((candidate) => candidate.id !== replacesTurnId), turn] }
          : current,
      );
      setLive((current) => (current?.conversationId === target ? null : current));
      // 标题在第一轮回答后由后台生成，列表顺序也变了，重新读一次列表；这一轮的结果已写进会话缓存，不必重读详情。
      void queryClient.invalidateQueries({ queryKey: chatKeys.conversations });
    } catch {
      update({ failed: true });
    }
  }

  /** 停止生成：拿到这一轮的 id 之后才能停；停止请求失败时不打扰员工。 */
  async function stop() {
    if (live?.turnId !== undefined && conversationId !== null) {
      await stopTurn(conversationId, live.turnId).catch(() => undefined);
    }
  }

  const visibleLive = live && live.conversationId === conversationId ? live : null;
  return {
    /** 当前会话里正在生成的一轮；别的会话的不算。 */
    live: visibleLive,
    asking: visibleLive !== null && !visibleLive.failed,
    ask,
    stop,
  };
}
