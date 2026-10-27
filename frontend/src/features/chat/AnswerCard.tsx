import { SearchX, X } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import type { Answer, Citation } from "./api";
import { FeedbackBar } from "./FeedbackBar";

/** 一条回答：正文里的 [n] 可点开原文，下方列出来源和点赞点踩。拒答时显示“没有找到答案”，也可以反馈没帮助。 */
export function AnswerCard({ answer }: { answer: Answer }) {
  const [opened, setOpened] = useState<Citation | null>(null);

  if (answer.refused) {
    return (
      <article aria-label="回答" className="flex flex-col gap-2 rounded-lg border bg-card p-4">
        <div className="flex gap-3">
          <SearchX aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
          <div className="flex flex-col gap-1">
            <h2 className="font-medium">没有找到答案</h2>
            <p className="text-sm text-muted-foreground">{answer.text}</p>
          </div>
        </div>
        <FeedbackBar answerId={answer.id} allowUp={false} />
      </article>
    );
  }

  const byIndex = new Map(answer.citations.map((citation) => [citation.index, citation]));
  // 拆出 [n]：奇数位是编号，偶数位是正文。后端只保留真实存在的引用，这里仍跳过对不上的编号。
  const parts = answer.text.split(/\[(\d+)\]/);

  return (
    <article aria-label="回答" className="flex flex-col gap-3 rounded-lg border bg-card p-4">
      <p className="leading-relaxed">
        {parts.map((part, position) => {
          if (position % 2 === 0) {
            return part;
          }
          const citation = byIndex.get(Number(part));
          return (
            citation && (
              <button
                key={position}
                type="button"
                aria-label={`查看引用 ${citation.index}`}
                onClick={() => setOpened(citation)}
                className="mx-0.5 rounded-sm bg-accent px-1 align-super text-xs text-accent-foreground"
              >
                {citation.index}
              </button>
            )
          );
        })}
      </p>
      {opened && (
        <section aria-label="引用原文" className="flex flex-col gap-1 rounded-md bg-secondary p-3 text-sm">
          <div className="flex items-start justify-between gap-2">
            <div>
              <p className="font-medium">{opened.documentName}</p>
              <p className="text-muted-foreground">{opened.headingPath}</p>
            </div>
            <Button variant="ghost" size="sm" aria-label="收起原文" onClick={() => setOpened(null)}>
              <X aria-hidden="true" className="size-4" />
            </Button>
          </div>
          <p className="font-serif leading-relaxed">{opened.excerpt}</p>
        </section>
      )}
      <ol aria-label="来源" className="flex flex-col gap-1 border-t pt-3 text-sm">
        {answer.citations.map((citation) => (
          <li key={citation.index}>
            <button
              type="button"
              onClick={() => setOpened(citation)}
              className="text-left text-muted-foreground hover:text-foreground"
            >
              [{citation.index}] {citation.documentName} · {citation.headingPath}
            </button>
          </li>
        ))}
      </ol>
      <FeedbackBar answerId={answer.id} />
    </article>
  );
}
