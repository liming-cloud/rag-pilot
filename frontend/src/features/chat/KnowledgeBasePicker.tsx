import { ChevronDown, Library, Lock } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { ConversationKnowledgeBase } from "./api";

/**
 * 会话级的知识库选择（D-036、D-040）：顶部只占一个按钮，写着选了哪些库；点开在弹窗里搜索、勾选，至少留一个，
 * 点“确定”才生效。只列已授权的库；会话里选过、后来被收回授权的库标“已无权限”，不能再选，后续提问也不再检索它。
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
  /** 按钮旁的一句说明，比如“从下一个问题起生效”。 */
  note?: string;
}) {
  const [open, setOpen] = useState(false);
  const accessible = options.filter((option) => option.accessible);
  const chosen = accessible.filter((option) => selected.includes(option.id));
  const revoked = options.length - accessible.length;

  return (
    <div role="group" aria-label="知识库" className="flex flex-wrap items-center gap-2">
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="h-8 max-w-full"
        disabled={disabled || accessible.length === 0}
        onClick={() => setOpen(true)}
      >
        <Library aria-hidden="true" className="size-4" />
        <span className="truncate">{summary(chosen, accessible.length)}</span>
        <ChevronDown aria-hidden="true" className="size-4 opacity-60" />
      </Button>
      {revoked > 0 && <span className="text-xs text-muted-foreground">{revoked} 个库已无权限</span>}
      {note && <span className="text-xs text-muted-foreground">{note}</span>}
      <Dialog open={open} onOpenChange={setOpen}>
        {open && (
          <PickerDialog
            options={options}
            selected={chosen.map((option) => option.id)}
            onConfirm={(ids) => {
              setOpen(false);
              if (!sameSet(ids, selected)) onChange(ids);
            }}
            onCancel={() => setOpen(false)}
          />
        )}
      </Dialog>
    </div>
  );
}

/** 按钮上的文字：全选时写“全部知识库（N）”，一个时写库名，多个时写“第一个 等 N 个”。 */
function summary(chosen: ConversationKnowledgeBase[], accessibleCount: number) {
  if (chosen.length === 0) return "选择知识库";
  if (chosen.length === accessibleCount && accessibleCount > 1) return `全部知识库（${accessibleCount}）`;
  if (chosen.length === 1) return chosen[0]!.name;
  return `${chosen[0]!.name} 等 ${chosen.length} 个`;
}

const sameSet = (a: number[], b: number[]) => a.length === b.length && a.every((id) => b.includes(id));

function PickerDialog({
  options,
  selected,
  onConfirm,
  onCancel,
}: {
  options: ConversationKnowledgeBase[];
  selected: number[];
  onConfirm: (ids: number[]) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<number[]>(selected);
  const [keyword, setKeyword] = useState("");
  const accessibleIds = options.filter((option) => option.accessible).map((option) => option.id);
  const visible = options.filter((option) => option.name.includes(keyword.trim()));
  const allChosen = draft.length === accessibleIds.length;

  const toggle = (id: number) =>
    setDraft(draft.includes(id) ? draft.filter((candidate) => candidate !== id) : [...draft, id]);

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>选择知识库</DialogTitle>
        <DialogDescription>这个会话只从选中的库里找答案，至少选一个。</DialogDescription>
      </DialogHeader>
      <div className="flex items-center gap-2">
        <Input
          aria-label="搜索知识库"
          placeholder="搜索知识库"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <Button type="button" variant="ghost" size="sm" onClick={() => setDraft(allChosen ? [] : accessibleIds)}>
          {allChosen ? "全不选" : "全选"}
        </Button>
      </div>
      <ul aria-label="可选的知识库" className="flex max-h-72 flex-col overflow-y-auto rounded-md border">
        {visible.length === 0 && <li className="p-3 text-sm text-muted-foreground">没有找到知识库</li>}
        {visible.map((option) => (
          <li key={option.id} className="border-b last:border-b-0">
            <Label
              className="flex items-center gap-2 px-3 py-2 font-normal"
              title={option.accessible ? undefined : "授权已收回，后续提问不再检索这个库"}
            >
              <Checkbox
                checked={option.accessible && draft.includes(option.id)}
                disabled={!option.accessible}
                onCheckedChange={() => toggle(option.id)}
              />
              <span className="flex-1 truncate">{option.name}</span>
              {!option.accessible && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Lock aria-hidden="true" className="size-3" />
                  已无权限
                </span>
              )}
            </Label>
          </li>
        ))}
      </ul>
      <DialogFooter className="items-center">
        <span className="mr-auto text-sm text-muted-foreground">已选 {draft.length} 个</span>
        <Button type="button" variant="ghost" size="sm" onClick={onCancel}>
          取消
        </Button>
        <Button type="button" size="sm" disabled={draft.length === 0} onClick={() => onConfirm(draft)}>
          确定
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
