import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import {
  restoreSetting,
  SETTING_META,
  type SettingItem,
  type SettingSource,
  SOURCE_LABELS,
  type SystemSettings,
} from "./api";
import { useRefreshAfterChange, EditDialog } from "./EditSettingDialog";

export function SourceBadge({ source }: { source: SettingSource }) {
  return <Badge variant={source === "DATABASE" ? "default" : "outline"}>{SOURCE_LABELS[source]}</Badge>;
}

export function formatTime(value: string) {
  return new Date(value).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" });
}

export function ItemsTab({ settings, group }: { settings: SystemSettings; group: "brand" | "chat" }) {
  const [editing, setEditing] = useState<SettingItem | null>(null);
  const [restoring, setRestoring] = useState<SettingItem | null>(null);
  const items = settings.items.filter((item) => SETTING_META[item.key].group === group);

  return (
    <section aria-label={group === "brand" ? "品牌与登录页" : "问答页"} className="flex flex-col gap-3">
      {group === "chat" && (
        <p className="text-sm text-muted-foreground">
          默认值来自当前行业包“{settings.industryPack.name}”。推荐问题按顺序显示，最多{" "}
          {SETTING_META.SUGGESTED_QUESTIONS.maxItems} 个；员工看不到相关知识库时，点了也会如实说找不到依据。
        </p>
      )}
      <ul className="divide-y rounded-lg border bg-card">
        {items.map((item) => {
          const meta = SETTING_META[item.key];
          return (
            <li key={item.key} aria-label={meta.label} className="flex flex-col gap-2 px-4 py-3 md:flex-row md:gap-4">
              <div className="md:w-48 md:shrink-0">
                <div className="font-medium">{meta.label}</div>
                <div className="text-xs text-muted-foreground">{meta.where}</div>
              </div>
              <div className="min-w-0 flex-1 text-sm">
                <SettingValue item={item} />
                <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <SourceBadge source={item.source} />
                  {item.source === "DATABASE" && item.updatedAt && (
                    <span>
                      {item.updatedBy} 于 {formatTime(item.updatedAt)} 修改
                    </span>
                  )}
                </div>
              </div>
              <Can permission={PERMISSIONS.SETTING_UPDATE}>
                <div className="flex shrink-0 items-start gap-1">
                  <Button variant="ghost" size="sm" aria-label={`修改${meta.label}`} onClick={() => setEditing(item)}>
                    修改
                  </Button>
                  {item.source === "DATABASE" && (
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`恢复${meta.label}的默认值`}
                      onClick={() => setRestoring(item)}
                    >
                      恢复默认
                    </Button>
                  )}
                </div>
              </Can>
            </li>
          );
        })}
      </ul>
      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        {editing && <EditDialog item={editing} onDone={() => setEditing(null)} />}
      </Dialog>
      <Dialog open={restoring !== null} onOpenChange={(open) => !open && setRestoring(null)}>
        {restoring && <RestoreDialog item={restoring} onDone={() => setRestoring(null)} />}
      </Dialog>
    </section>
  );
}

/** 文字整段显示、保留换行；推荐问题按顺序列出，一个都没有时说明不显示推荐问题。 */
export function SettingValue({ item }: { item: SettingItem }) {
  if (item.kind === "text") return <p className="break-words whitespace-pre-wrap">{item.value}</p>;
  if (item.value.length === 0) return <p className="text-muted-foreground">不显示推荐问题</p>;
  return (
    <ol className="flex list-decimal flex-col gap-0.5 pl-5">
      {item.value.map((question) => (
        <li key={question} className="break-words">
          {question}
        </li>
      ))}
    </ol>
  );
}

export function RestoreDialog({ item, onDone }: { item: SettingItem; onDone: () => void }) {
  const meta = SETTING_META[item.key];
  const refresh = useRefreshAfterChange();
  const restore = useMutation({
    mutationFn: () => restoreSetting(item.key),
    onSuccess: async () => {
      await refresh();
      onDone();
    },
  });
  const fallback =
    item.kind === "text"
      ? item.defaultValue
      : item.defaultValue.length > 0
        ? item.defaultValue.join(" / ")
        : "不显示推荐问题";

  return (
    <DialogContent className="max-w-md">
      <DialogHeader>
        <DialogTitle>恢复{meta.label}的默认值？</DialogTitle>
        <DialogDescription>
          界面上保存的值会被删除，改用{SOURCE_LABELS[item.defaultSource]}的值：{fallback}
        </DialogDescription>
      </DialogHeader>
      <ErrorMessage error={restore.error} />
      <DialogFooter>
        <Button variant="outline" onClick={onDone}>
          取消
        </Button>
        <Button onClick={() => restore.mutate()} disabled={restore.isPending}>
          恢复默认
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
