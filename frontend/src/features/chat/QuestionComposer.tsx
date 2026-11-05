import { ArrowUp, Square } from "lucide-react";
import { type FormEvent, type KeyboardEvent, useLayoutEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

/** 一个问题最多多少字，与后端的上限一致。 */
export const MAX_QUESTION_LENGTH = 500;
/** 输入框最多长到这么高（像素），再多就在框里滚动。 */
const MAX_HEIGHT = 200;

/**
 * 提问的输入框（#145 A2、A3）：随内容自动增高；Enter 发送、Shift+Enter 换行，输入法还在选字时按 Enter 不发送；
 * 最多 500 字，快到上限时显示还能输入多少字。生成中显示“停止生成”。
 */
export function QuestionComposer({
  value,
  onChange,
  onSubmit,
  asking,
  onStop,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  asking: boolean;
  onStop: () => void;
  placeholder: string;
}) {
  const field = useRef<HTMLTextAreaElement>(null);

  useLayoutEffect(() => {
    const element = field.current;
    if (!element) return;
    element.style.height = "auto";
    element.style.height = `${Math.min(element.scrollHeight, MAX_HEIGHT)}px`;
  }, [value]);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!asking && value.trim()) onSubmit();
  }

  function keyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (!asking && value.trim()) onSubmit();
    }
  }

  const left = MAX_QUESTION_LENGTH - value.length;
  return (
    <form onSubmit={submit} className="glass flex items-end gap-2 rounded-2xl border border-input py-1.5 pr-1.5 pl-4 shadow-md transition-shadow focus-within:border-primary/60 focus-within:shadow-glow">
      <Label htmlFor="question" className="sr-only">
        输入问题
      </Label>
      <Textarea
        ref={field}
        id="question"
        rows={1}
        value={value}
        maxLength={MAX_QUESTION_LENGTH}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={keyDown}
        aria-describedby="question-hint"
        className="min-h-11 flex-1 resize-none rounded-none border-0 bg-transparent px-0 py-2.5 text-base shadow-none focus:ring-0"
        placeholder={placeholder}
      />
      <span id="question-hint" className="sr-only">
        Enter 发送，Shift+Enter 换行
      </span>
      {left <= 50 && (
        <span role="status" className="self-center text-xs text-muted-foreground">
          还能输入 {left} 字
        </span>
      )}
      {asking ? (
        <Button type="button" size="icon" className="rounded-xl" aria-label="停止生成" onClick={onStop}>
          <Square aria-hidden="true" className="size-4 fill-current" />
        </Button>
      ) : (
        <Button type="submit" size="icon" className="rounded-xl" aria-label="发送" disabled={!value.trim()}>
          <ArrowUp aria-hidden="true" className="size-5" />
        </Button>
      )}
    </form>
  );
}
