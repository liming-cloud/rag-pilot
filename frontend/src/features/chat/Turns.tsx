import { RotateCcw } from "lucide-react";
import { type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { ErrorMessage } from "@/lib/errors";
import { AnswerCard, type RefusalHelp } from "./AnswerCard";
import { type Turn } from "./api";
import { type Live } from "./useConversationAsk";

export function Question({ text }: { text: string }) {
  return <p className="max-w-[85%] self-end rounded-2xl rounded-br-md bg-gradient-primary px-4 py-2.5 text-primary-foreground shadow-md">
      {text}
    </p>;
}

export function TurnView({ turn, actions, refusalHelp }: { turn: Turn; actions: ReactNode; refusalHelp: RefusalHelp }) {
  return (
    <section aria-label={`问答：${turn.question}`} className="flex flex-col gap-2">
      <Question text={turn.question} />
      {turn.status === "FAILED" && <ErrorMessage error="暂时无法回答，请稍后重试。" />}
      {turn.status === "STOPPED" && !turn.answer && (
        <p className="text-sm text-muted-foreground">已停止生成，没有留下可核对的内容。</p>
      )}
      {turn.answer && <AnswerCard answer={turn.answer} refusalHelp={refusalHelp} />}
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

/** 正在生成的一轮；失败时显示服务端给的原因，问题还在，可以重试（#145 A2）。 */
export function LiveTurnView({ live, onRetry }: { live: Live; onRetry: () => void }) {
  return (
    <section aria-label={`问答：${live.question}`} className="flex flex-col gap-2">
      <Question text={live.question} />
      {live.failed ? (
        <div className="flex items-center gap-2">
          <ErrorMessage error={live.error ?? "暂时无法回答，请稍后重试。"} />
          <Button variant="ghost" size="sm" onClick={onRetry}>
            <RotateCcw aria-hidden="true" className="size-4" />
            重试
          </Button>
        </div>
      ) : live.text ? (
        <p aria-live="polite" className="ai-border rounded-2xl p-5 leading-relaxed whitespace-pre-line shadow-md">
          {live.text}
          <span aria-hidden="true" className="ml-0.5 inline-block h-4 w-0.5 translate-y-0.5 animate-pulse bg-primary" />
        </p>
      ) : (
        <p role="status" className="glass flex w-fit items-center gap-3 rounded-full border px-4 py-2 text-sm text-muted-foreground shadow-sm">
          <span aria-hidden="true" className="flex gap-1">
            <span className="size-1.5 animate-bounce rounded-full bg-primary [animation-delay:-0.3s]" />
            <span className="size-1.5 animate-bounce rounded-full bg-brand [animation-delay:-0.15s]" />
            <span className="size-1.5 animate-bounce rounded-full bg-cyan" />
          </span>
          {live.status}
        </p>
      )}
    </section>
  );
}
