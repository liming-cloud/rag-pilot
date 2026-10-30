import { Check, Lock } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { ConversationKnowledgeBase } from "./api";

/**
 * 会话级的知识库选择：只列已授权的库，可多选，至少留一个。会话里选过、后来被收回授权的库标“已无权限”，
 * 不能再选，后续提问也不再检索它（D-036）。
 */
export function KnowledgeBasePicker({
  options,
  selected,
  onChange,
  disabled = false,
  note,
}: {
  options: ConversationKnowledgeBase[];
  selected: number[];
  onChange: (ids: number[]) => void;
  disabled?: boolean;
  /** 选择下方的一句说明，比如“从下一个问题起生效”。 */
  note?: string;
}) {
  function toggle(id: number) {
    const next = selected.includes(id) ? selected.filter((candidate) => candidate !== id) : [...selected, id];
    if (next.length > 0) {
      onChange(next);
    }
  }

  return (
    <div role="group" aria-label="知识库" className="flex flex-wrap items-center gap-2">
      <span className="text-sm text-muted-foreground">知识库</span>
      {options.map((option) => {
        const on = option.accessible && selected.includes(option.id);
        return (
          <Button
            key={option.id}
            type="button"
            variant={on ? "default" : "outline"}
            size="sm"
            className="h-8 rounded-full"
            aria-pressed={on}
            disabled={disabled || !option.accessible || (on && selected.length === 1)}
            title={!option.accessible ? "授权已收回，后续提问不再检索这个库" : undefined}
            onClick={() => toggle(option.id)}
          >
            {option.accessible ? (
              on && <Check aria-hidden="true" className="size-3.5" />
            ) : (
              <Lock aria-hidden="true" className="size-3.5" />
            )}
            {option.name}
            {!option.accessible && <span className="text-xs">已无权限</span>}
          </Button>
        );
      })}
      {note && <span className="text-xs text-muted-foreground">{note}</span>}
    </div>
  );
}
