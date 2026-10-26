import { ArrowUp } from "lucide-react";
import { Button } from "@/components/ui/button";

/**
 * 问答页骨架。按已选定的画布方案 A（对话式）在第 2 周补全：流式回答、引用编号与来源列表、
 * 查看引用原文、找不到答案、点赞点踩。
 */
export function ChatPage() {
  return (
    <div className="mx-auto flex h-dvh max-w-md flex-col">
      <header className="flex h-14 items-center justify-center border-b bg-card">
        <span className="font-medium">门店运营知识库</span>
      </header>
      <main className="flex flex-1 items-center justify-center p-6 text-center text-muted-foreground">
        问问门店规范、商品、促销，回答会标出原文出处。
      </main>
      <footer className="border-t p-3">
        <form className="flex items-center gap-2 rounded-full border border-input bg-card py-1 pr-1 pl-4">
          <label htmlFor="question" className="sr-only">
            输入问题
          </label>
          <input
            id="question"
            className="h-11 min-w-0 flex-1 bg-transparent text-base outline-none"
            placeholder="问问门店规范、商品、促销…"
          />
          <Button type="submit" size="icon" className="rounded-full" aria-label="发送">
            <ArrowUp aria-hidden="true" className="size-5" />
          </Button>
        </form>
      </footer>
    </div>
  );
}
