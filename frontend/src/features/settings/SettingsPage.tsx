import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Info, Lock, Plus, Trash2 } from "lucide-react";
import { type FormEvent, type ReactNode, useState } from "react";
import { useSearchParams } from "react-router";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import {
  fetchSettings,
  type IndustryPack,
  restoreSetting,
  saveSetting,
  SETTING_META,
  type SettingItem,
  settingProblem,
  SOURCE_LABELS,
  type SettingSource,
  type SystemSettings,
} from "./api";

const TABS = [
  { key: "brand", label: "品牌与登录页" },
  { key: "chat", label: "问答页" },
  { key: "pack", label: "行业包" },
] as const;

type Tab = (typeof TABS)[number]["key"];

/**
 * 系统设置（#122、D-045）：分“品牌与登录页”“问答页”“行业包”三个标签，标签记在地址里。前两个标签的项可以改，
 * 改过的以界面为准，可以恢复默认（先确认）；行业包里影响检索和回答质量的内容只读。
 */
export function SettingsPage() {
  const settings = useQuery({ queryKey: ["settings"], queryFn: fetchSettings });
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.some((item) => item.key === params.get("tab")) ? (params.get("tab") as Tab) : "brand";

  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-4 p-6 md:p-8">
      <div>
        <h1 className="font-serif text-2xl font-semibold">系统设置</h1>
        <p className="text-sm text-muted-foreground">
          在这里改过的项以这里为准，没改过的用部署配置或行业包的默认值。保存后刷新页面即可看到，不用重启。
        </p>
      </div>
      {settings.isError && <p className="text-destructive">{settings.error.message}</p>}
      {settings.data && (
        <>
          <div role="tablist" aria-label="系统设置" className="flex gap-1 border-b">
            {TABS.map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={tab === item.key}
                onClick={() => setParams(item.key === "brand" ? {} : { tab: item.key })}
                className={cn(
                  "-mb-px border-b-2 px-4 py-2 text-sm",
                  tab === item.key ? "border-primary font-medium" : "border-transparent text-muted-foreground",
                )}
              >
                {item.label}
              </button>
            ))}
          </div>
          {tab === "pack" ? (
            <PackTab pack={settings.data.industryPack} statsZone={settings.data.statsZone} />
          ) : (
            <ItemsTab settings={settings.data} group={tab} />
          )}
        </>
      )}
    </main>
  );
}

function SourceBadge({ source }: { source: SettingSource }) {
  return <Badge variant={source === "DATABASE" ? "default" : "outline"}>{SOURCE_LABELS[source]}</Badge>;
}

function formatTime(value: string) {
  return new Date(value).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" });
}

function ItemsTab({ settings, group }: { settings: SystemSettings; group: "brand" | "chat" }) {
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
function SettingValue({ item }: { item: SettingItem }) {
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

/** 保存后刷新设置页，也让登录页、顶栏和问答页下次读到新值。 */
function useRefreshAfterChange() {
  const queryClient = useQueryClient();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: ["settings"] });
    await queryClient.invalidateQueries({ queryKey: ["branding"] });
    await queryClient.invalidateQueries({ queryKey: ["chat-texts"] });
  };
}

function EditDialog({ item, onDone }: { item: SettingItem; onDone: () => void }) {
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
        {save.isError && (
          <p role="alert" className="text-sm text-destructive">
            {save.error.message}
          </p>
        )}
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

function Counter({ length, max }: { length: number; max: number }) {
  return (
    <span className={cn("text-xs", length > max ? "text-destructive" : "text-muted-foreground")}>
      {length}/{max}
    </span>
  );
}

function TextField({
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
function QuestionList({
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

function RestoreDialog({ item, onDone }: { item: SettingItem; onDone: () => void }) {
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
      {restore.isError && (
        <p role="alert" className="text-sm text-destructive">
          {restore.error.message}
        </p>
      )}
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

const VOCABULARY_PAGE_SIZE = 10;

/** 行业包只读：提示词角色、改写提示、行业词表影响检索和回答质量，要改须改行业包并跑评测（D-045）。 */
function PackTab({ pack, statsZone }: { pack: IndustryPack; statsZone: string }) {
  return (
    <section aria-label="行业包" className="flex flex-col gap-4">
      <p role="note" className="flex items-start gap-2 rounded-md bg-accent px-4 py-3 text-sm text-accent-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span>
          当前行业包：<strong>{pack.name}</strong>（{pack.code}），由部署配置 RAG_INDUSTRY_PACK
          选择，换行业包需要重启。下面的内容直接影响检索和回答质量，这里只能查看；要修改请改行业包并跑评测。
        </span>
      </p>
      <dl className="grid gap-x-4 gap-y-3 rounded-lg border bg-card px-4 py-3 text-sm md:grid-cols-[10rem_1fr]">
        <ReadOnly term="回答的角色">{pack.answerRole}</ReadOnly>
        <ReadOnly term="引用示例">{pack.citationExample}</ReadOnly>
        <ReadOnly term="改写的角色">{pack.rewriteRole}</ReadOnly>
        <ReadOnly term="改写的行业提示">
          <ul className="list-disc pl-5">
            {pack.rewriteHints.map((hint) => (
              <li key={hint}>{hint}</li>
            ))}
          </ul>
        </ReadOnly>
        <ReadOnly term="统计时区">
          {statsZone}
          <span className="ml-2 text-xs text-muted-foreground">部署配置 rag.answering.stats-zone，不属于行业包</span>
        </ReadOnly>
      </dl>
      <Vocabulary entries={pack.vocabulary} />
    </section>
  );
}

function ReadOnly({ term, children }: { term: string; children: ReactNode }) {
  return (
    <>
      <dt className="flex items-center gap-1 text-muted-foreground">
        <Lock aria-hidden="true" className="size-3" />
        {term}
      </dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </>
  );
}

/** 行业词表可能有几百组：按上位词或包含的词搜索，分页显示。 */
function Vocabulary({ entries }: { entries: IndustryPack["vocabulary"] }) {
  const [keyword, setKeyword] = useState("");
  const [paging, setPaging] = useState({ page: 1, size: VOCABULARY_PAGE_SIZE });
  const word = keyword.trim();
  const matched = entries.filter(
    (entry) => !word || entry.term.includes(word) || entry.members.some((member) => member.includes(word)),
  );
  const shown = matched.slice((paging.page - 1) * paging.size, paging.page * paging.size);
  // 说明里的例子取自当前行业包的词表，界面代码不写死行业词（D-045）。
  const example = entries.find((entry) => entry.members.length > 0);

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 className="font-medium">行业词表</h2>
          <p className="text-xs text-muted-foreground">
            问题里出现右边的词时，检索前把左边的词加进去
            {example && `，例如问“${example.members[0]}”也能找到写着“${example.term}”的资料`}。共 {entries.length} 组。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Label htmlFor="vocabulary-keyword" className="sr-only">
            搜索词表
          </Label>
          <Input
            id="vocabulary-keyword"
            placeholder="搜索词表"
            className="w-48"
            value={keyword}
            onChange={(event) => {
              setKeyword(event.target.value);
              setPaging((current) => ({ ...current, page: 1 }));
            }}
          />
        </div>
      </div>
      <div className="rounded-lg border bg-card">
        <Table aria-label="行业词表" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-32">检索时加上</TableHead>
              <TableHead>问题里出现这些词时</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((entry) => (
              <TableRow key={entry.term}>
                <TableCell className="font-medium">{entry.term}</TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {entry.members.map((member) => (
                      <Badge
                        key={member}
                        variant="outline"
                        className={cn(word && member.includes(word) && "border-primary")}
                      >
                        {member}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={2} className="py-6 text-center text-muted-foreground">
                  没有找到包含“{word}”的词
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
      {matched.length > VOCABULARY_PAGE_SIZE && (
        <Pagination page={paging.page} size={paging.size} total={matched.length} onChange={setPaging} />
      )}
    </div>
  );
}
