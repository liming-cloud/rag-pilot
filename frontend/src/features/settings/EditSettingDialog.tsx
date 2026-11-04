import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { chatKeys } from "@/features/chat/api";
import { brandingKey } from "@/lib/branding";
import { ErrorMessage } from "@/lib/errors";
import { cn } from "@/lib/utils";
import { saveSetting, SETTING_META, type SettingItem, settingProblem, settingsKeys, SOURCE_LABELS } from "./api";

/** 保存后刷新设置页，也让登录页、顶栏和问答页下次读到新值。 */
export function useRefreshAfterChange() {
  const queryClient = useQueryClient();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: settingsKeys.all });
    await queryClient.invalidateQueries({ queryKey: brandingKey });
    await queryClient.invalidateQueries({ queryKey: chatKeys.texts });
  };
}

export function EditDialog({ item, onDone }: { item: SettingItem; onDone: () => void }) {
  const meta = SETTING_META[item.key];
  const refresh = useRefreshAfterChange();
  const [text, setText] = useState(item.kind === "text" ? item.value : "");
  const [list, setList] = useState(item.kind === "list" ? item.value : []);
  const value = item.kind === "text" ? text : list;
  const problem = settingProblem(item.key, value);
  const unchanged =
    JSON.stringify(item.kind === "text" ? text.trim() : list.map((q) => q.trim())) === JSON.stringify(item.value);
  const save = useMutation({
    mutationFn: () => saveSetting(item.key, value),
    onSuccess: async () => {
      await refresh();
      onDone();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!problem && !unchanged) save.mutate();
  }

  return (
    <DialogContent className="max-w-xl">
      <DialogHeader>
        <DialogTitle>修改{meta.label}</DialogTitle>
        <DialogDescription>显示在{meta.where}。</DialogDescription>
      </DialogHeader>
      <form onSubmit={submit} className="flex flex-col gap-3">
        {item.kind === "text" ? (
          <TextField
            label={meta.label}
            value={text}
            onChange={setText}
            maxLength={meta.maxLength}
            multiline={meta.multiline}
          />
        ) : (
          <QuestionList value={list} onChange={setList} maxItems={meta.maxItems ?? 4} maxLength={meta.maxLength} />
        )}
        <p className="text-xs text-muted-foreground">
          默认值（{SOURCE_LABELS[item.defaultSource]}）：
          {item.kind === "text"
            ? item.defaultValue
            : item.defaultValue.length > 0
              ? item.defaultValue.join(" / ")
              : "不显示推荐问题"}
        </p>
        {problem && !unchanged && (
          <p role="alert" className="text-sm text-destructive">
            {problem}
          </p>
        )}
        <ErrorMessage error={save.error} />
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" disabled={save.isPending || unchanged || problem !== null}>
            {save.isPending ? "保存中…" : "保存"}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}

export function Counter({ length, max }: { length: number; max: number }) {
  return (
    <span className={cn("text-xs", length > max ? "text-destructive" : "text-muted-foreground")}>
      {length}/{max}
    </span>
  );
}

export function TextField({
  label,
  value,
  onChange,
  maxLength,
  multiline,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  maxLength: number;
  multiline?: boolean;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <Label htmlFor="setting-value">{label}</Label>
        <Counter length={value.trim().length} max={maxLength} />
      </div>
      {multiline ? (
        <Textarea id="setting-value" rows={3} value={value} onChange={(event) => onChange(event.target.value)} />
      ) : (
        <Input id="setting-value" value={value} onChange={(event) => onChange(event.target.value)} />
      )}
    </div>
  );
}

/** 推荐问题：逐条编辑，可以上移、下移、删除，最多 maxItems 条。 */
export function QuestionList({
  value,
  onChange,
  maxItems,
  maxLength,
}: {
  value: string[];
  onChange: (value: string[]) => void;
  maxItems: number;
  maxLength: number;
}) {
  const set = (index: number, question: string) => onChange(value.map((item, at) => (at === index ? question : item)));
  const move = (index: number, offset: number) => {
    const next = [...value];
    [next[index], next[index + offset]] = [next[index + offset]!, next[index]!];
    onChange(next);
  };

  return (
    <div className="flex flex-col gap-2">
      {value.length === 0 && <p className="text-sm text-muted-foreground">没有推荐问题，问答页的空状态只显示说明。</p>}
      {value.map((question, index) => (
        <div key={index} className="flex items-center gap-1">
          <Label htmlFor={`question-${index}`} className="w-14 shrink-0 text-sm text-muted-foreground">
            问题 {index + 1}
          </Label>
          <Input
            id={`question-${index}`}
            className="min-w-0 flex-1"
            value={question}
            onChange={(event) => set(index, event.target.value)}
          />
          <Counter length={question.trim().length} max={maxLength} />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={`上移问题 ${index + 1}`}
            disabled={index === 0}
            onClick={() => move(index, -1)}
          >
            <ArrowUp aria-hidden="true" className="size-4" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={`下移问题 ${index + 1}`}
            disabled={index === value.length - 1}
            onClick={() => move(index, 1)}
          >
            <ArrowDown aria-hidden="true" className="size-4" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={`删除问题 ${index + 1}`}
            onClick={() => onChange(value.filter((_, at) => at !== index))}
          >
            <Trash2 aria-hidden="true" className="size-4" />
          </Button>
        </div>
      ))}
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="self-start"
        disabled={value.length >= maxItems}
        onClick={() => onChange([...value, ""])}
      >
        <Plus aria-hidden="true" className="size-4" />
        {value.length >= maxItems ? `最多 ${maxItems} 个` : "添加问题"}
      </Button>
    </div>
  );
}
