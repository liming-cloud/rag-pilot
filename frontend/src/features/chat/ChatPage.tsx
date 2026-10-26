import { useQueryClient } from "@tanstack/react-query";
import { ArrowUp } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { fetchKnowledgeBases } from "@/features/knowledge/api";
import { type Answer, askQuestion } from "./api";
import { AnswerCard } from "./AnswerCard";

type Turn = { id: number; question: string } & (
  | { status: "pending" }
  | { status: "done"; answer: Answer }
  | { status: "failed" }
);

/**
 * 问答页，按画布方案 A（对话式、手机优先）。当前是非流式整段回答；流式输出随第 3 周后端流式接口一起做。
 */
export function ChatPage() {
  const queryClient = useQueryClient();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState("");
  const asking = turns.some((turn) => turn.status === "pending");

  async function submit(event: FormEvent) {
    event.preventDefault();
    const question = draft.trim();
    if (!question || asking) {
      return;
    }
    const id = Date.now();
    setDraft("");
    setTurns((current) => [...current, { id, question, status: "pending" }]);
    const settle = (next: Turn) => setTurns((current) => current.map((turn) => (turn.id === id ? next : turn)));
    try {
      // 试点阶段问全部知识库；接入登录后由后端按授权过滤，前端不再传库列表。
      const knowledgeBases = await queryClient.ensureQueryData({
        queryKey: ["knowledge-bases"],
        queryFn: fetchKnowledgeBases,
      });
      const answer = await askQuestion(
        question,
        knowledgeBases.map((knowledgeBase) => knowledgeBase.id),
      );
      settle({ id, question, status: "done", answer });
    } catch {
      settle({ id, question, status: "failed" });
    }
  }

  return (
    <div className="mx-auto flex h-dvh max-w-md flex-col">
      <header className="flex h-14 shrink-0 items-center justify-center border-b bg-card">
        <span className="font-medium">门店运营知识库</span>
      </header>
      <main className="flex flex-1 flex-col gap-4 overflow-y-auto p-4">
        {turns.length === 0 && (
          <p className="m-auto text-center text-muted-foreground">问问门店规范、商品、促销，回答会标出原文出处。</p>
        )}
        {turns.map((turn) => (
          <section key={turn.id} className="flex flex-col gap-2">
            <p className="max-w-[85%] self-end rounded-lg bg-accent px-3 py-2 text-accent-foreground">
              {turn.question}
            </p>
            {turn.status === "pending" && (
              <p role="status" className="text-sm text-muted-foreground">
                正在查找资料…
              </p>
            )}
            {turn.status === "failed" && (
              <p role="alert" className="text-sm text-destructive">
                暂时无法回答，请稍后重试。
              </p>
            )}
            {turn.status === "done" && <AnswerCard answer={turn.answer} />}
          </section>
        ))}
      </main>
      <footer className="shrink-0 border-t p-3">
        <form
          onSubmit={submit}
          className="flex items-center gap-2 rounded-full border border-input bg-card py-1 pr-1 pl-4"
        >
          <label htmlFor="question" className="sr-only">
            输入问题
          </label>
          <input
            id="question"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            className="h-11 min-w-0 flex-1 bg-transparent text-base outline-none"
            placeholder="问问门店规范、商品、促销…"
          />
          <Button type="submit" size="icon" className="rounded-full" aria-label="发送" disabled={asking}>
            <ArrowUp aria-hidden="true" className="size-5" />
          </Button>
        </form>
      </footer>
    </div>
  );
}
