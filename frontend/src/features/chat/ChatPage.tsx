import { ArrowUp } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { type Answer, askStream } from "./api";
import { AnswerCard } from "./AnswerCard";

type Turn = { id: number; question: string } & (
  | { status: "pending"; note?: string }
  | { status: "streaming"; text: string }
  | { status: "done"; answer: Answer }
  | { status: "failed" }
);

/**
 * 问答页，按画布方案 A（对话式、手机优先）。回答边生成边显示，结束后换成带引用的最终结果。
 */
export function ChatPage() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState("");
  const asking = turns.some((turn) => turn.status === "pending" || turn.status === "streaming");

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
    let text = "";
    try {
      const answer = await askStream(
        question,
        (delta) => {
          text += delta;
          settle({ id, question, status: "streaming", text });
        },
        (note) => settle({ id, question, status: "pending", note }),
      );
      settle({ id, question, status: "done", answer });
    } catch {
      settle({ id, question, status: "failed" });
    }
  }

  return (
    <div className="mx-auto flex h-full max-w-md flex-col">
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
                {turn.note ?? "正在查找资料…"}
              </p>
            )}
            {turn.status === "streaming" && (
              <p aria-live="polite" className="rounded-lg border bg-card p-4 leading-relaxed">
                {turn.text}
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
          <Label htmlFor="question" className="sr-only">
            输入问题
          </Label>
          <Input
            id="question"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            className="h-11 flex-1 rounded-none border-0 bg-transparent px-0 text-base focus:ring-0"
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
