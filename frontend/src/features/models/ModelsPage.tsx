import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Lock, Plus, XCircle } from "lucide-react";
import { type FormEvent, useState } from "react";
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
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Can, useCan } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import {
  createProvider,
  deleteProvider,
  fetchAvailableModels,
  fetchModelChanges,
  fetchModelSettings,
  type ModelChange,
  type ModelSettings,
  type Provider,
  restoreChange,
  saveUsage,
  type Source,
  type TestResult,
  testModel,
  updateProvider,
  USAGE_LABELS,
  type UsageConfig,
} from "./api";

const TABS = [
  { key: "usages", label: "用途与模型" },
  { key: "providers", label: "服务商" },
  { key: "changes", label: "变更记录" },
] as const;

type Tab = (typeof TABS)[number]["key"];

/**
 * 模型管理（#120、D-043）：分“用途与模型”“服务商”“变更记录”三个标签，标签记在地址里，刷新后不丢。
 * 换模型在弹窗里做：从服务商的模型列表里选（不提供列表时手填），“测试并保存”一步完成，测不通不保存；
 * 恢复变更前先确认。嵌入模型只显示。
 */
export function ModelsPage() {
  const settings = useQuery({ queryKey: ["model-settings"], queryFn: fetchModelSettings });
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.some((item) => item.key === params.get("tab")) ? (params.get("tab") as Tab) : "usages";

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-4 p-6 md:p-8">
      <div>
        <h1 className="font-serif text-2xl font-semibold">模型管理</h1>
        <p className="text-sm text-muted-foreground">
          在界面保存过的用途以这里为准，没保存过的用环境变量。保存后下一次请求就用新模型，不用重启。
        </p>
      </div>
      {settings.isError && <p className="text-destructive">{settings.error.message}</p>}
      {settings.data && (
        <>
          {!settings.data.masterKeyConfigured && (
            <p
              role="status"
              className="flex items-start gap-2 rounded-md bg-accent px-4 py-3 text-sm text-accent-foreground"
            >
              <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              没有设置主密钥
              RAG_MASTER_KEY，不能在界面保存服务商密钥；不需要密钥的服务商（如内网自建）照常可用。设置后重启即可。
            </p>
          )}
          <div role="tablist" aria-label="模型管理" className="flex gap-1 border-b">
            {TABS.map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={tab === item.key}
                onClick={() => setParams(item.key === "usages" ? {} : { tab: item.key })}
                className={cn(
                  "-mb-px border-b-2 px-4 py-2 text-sm",
                  tab === item.key ? "border-primary font-medium" : "border-transparent text-muted-foreground",
                )}
              >
                {item.label}
              </button>
            ))}
          </div>
          {tab === "usages" && <UsagesTab settings={settings.data} />}
          {tab === "providers" && <ProvidersTab settings={settings.data} />}
          {tab === "changes" && <ChangesTab />}
        </>
      )}
    </main>
  );
}

function SourceBadge({ source }: { source: Source }) {
  return source === "DATABASE" ? <Badge>界面配置</Badge> : <Badge variant="outline">环境变量</Badge>;
}

/**
 * 模型名常带组织前缀（如 deepseek-ai/DeepSeek-V3）：名称在上、前缀用小字放在下面，窄列里也能看清关键部分；
 * 不折行，太长时截断，悬停看全名。
 */
function ModelName({ model }: { model: string }) {
  const slash = model.lastIndexOf("/");
  return (
    <span title={model} className="flex min-w-0 flex-col">
      <span className="truncate font-mono text-sm">{model.slice(slash + 1)}</span>
      {slash > 0 && <span className="truncate text-xs text-muted-foreground">{model.slice(0, slash)}</span>}
    </span>
  );
}

function UsagesTab({ settings }: { settings: ModelSettings }) {
  const [editing, setEditing] = useState<UsageConfig | null>(null);

  return (
    <section aria-label="用途与模型" className="flex flex-col gap-3">
      <div className="rounded-lg border bg-card">
        <Table aria-label="各用途的模型" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[30%]">用途</TableHead>
              <TableHead className="w-[22%]">服务商</TableHead>
              <TableHead>模型</TableHead>
              <TableHead className="w-24">来源</TableHead>
              <TableHead className="w-20 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.usages.map((usage) => (
              <TableRow key={usage.usage}>
                <TableCell className="py-3">
                  <div className="font-medium">{USAGE_LABELS[usage.usage].name}</div>
                  <div className="text-xs text-muted-foreground">{USAGE_LABELS[usage.usage].description}</div>
                </TableCell>
                <TableCell className="truncate" title={usage.providerName}>
                  {usage.providerName}
                </TableCell>
                <TableCell>
                  <ModelName model={usage.model} />
                </TableCell>
                <TableCell>
                  <SourceBadge source={usage.source} />
                </TableCell>
                <TableCell className="text-right">
                  <Can permission="model:update">
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`更换 ${USAGE_LABELS[usage.usage].name}`}
                      onClick={() => setEditing(usage)}
                    >
                      更换
                    </Button>
                  </Can>
                </TableCell>
              </TableRow>
            ))}
            <TableRow className="text-muted-foreground">
              <TableCell className="py-3">
                <div className="font-medium text-foreground">嵌入</div>
                <div className="text-xs">把资料和问题变成向量；换它要重建索引，后续版本提供</div>
              </TableCell>
              <TableCell className="truncate">{settings.embedding.providerName}</TableCell>
              <TableCell>
                <ModelName model={settings.embedding.model} />
                <span className="text-xs">{settings.embedding.dimensions} 维</span>
              </TableCell>
              <TableCell>
                <Badge variant="outline">
                  <Lock aria-hidden="true" className="size-3" />
                  只读
                </Badge>
              </TableCell>
              <TableCell />
            </TableRow>
          </TableBody>
        </Table>
      </div>
      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        {editing && <UsageDialog usage={editing} providers={settings.providers} onDone={() => setEditing(null)} />}
      </Dialog>
    </section>
  );
}

const ENV = "env";

/**
 * 换一个用途的模型：选服务商，从它的模型列表里选或手填模型名；“测试并保存”先测、通了才保存，
 * 也可以“只测试”。
 */
function UsageDialog({ usage, providers, onDone }: { usage: UsageConfig; providers: Provider[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const canTest = useCan("model:test");
  const [providerValue, setProviderValue] = useState(usage.providerId === null ? ENV : String(usage.providerId));
  const [model, setModel] = useState(usage.model);
  const [result, setResult] = useState<{ key: string; value: TestResult } | null>(null);
  const providerId = providerValue === ENV ? null : Number(providerValue);
  const trimmed = model.trim();
  const key = `${providerValue}|${trimmed}`;
  const available = useQuery({
    queryKey: ["available-models", providerValue],
    queryFn: () => fetchAvailableModels(providerId),
    retry: false,
    staleTime: 60_000,
  });
  // 输入框里正好是列表里的某个模型（比如打开时的当前模型、刚点选的）时列出全部，输入了别的文字才筛选。
  const all = available.data ?? [];
  const filter = all.includes(trimmed) ? "" : trimmed.toLowerCase();
  const matches = all.filter((name) => name.toLowerCase().includes(filter));

  const test = useMutation({
    mutationFn: () => testModel(usage.usage, providerId, trimmed),
    onSuccess: (value) => setResult({ key, value }),
  });
  const testAndSave = useMutation({
    mutationFn: async () => {
      const value = await testModel(usage.usage, providerId, trimmed);
      setResult({ key, value });
      if (!value.ok) return false;
      await saveUsage(usage.usage, providerId, trimmed);
      return true;
    },
    onSuccess: async (saved) => {
      if (!saved) return;
      await queryClient.invalidateQueries({ queryKey: ["model-settings"] });
      await queryClient.invalidateQueries({ queryKey: ["model-changes"] });
      onDone();
    },
  });
  const busy = test.isPending || testAndSave.isPending;
  const label = USAGE_LABELS[usage.usage].name;
  const shown = result?.key === key ? result.value : null;

  return (
    <DialogContent className="max-w-xl">
      <DialogHeader>
        <DialogTitle>更换{label}模型</DialogTitle>
        <DialogDescription>{USAGE_LABELS[usage.usage].description}。保存前会先测通，保存后立即生效。</DialogDescription>
      </DialogHeader>
      <Label className="flex flex-col gap-1 text-sm">
        服务商
        <NativeSelect value={providerValue} onChange={(event) => setProviderValue(event.target.value)}>
          {providers.map((provider) => (
            <NativeSelectOption key={provider.id ?? ENV} value={provider.id === null ? ENV : provider.id}>
              {provider.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Label>
      <div className="flex flex-col gap-1.5">
        <Label className="flex flex-col gap-1 text-sm">
          模型名称
          <Input
            value={model}
            className="font-mono"
            placeholder="从下面选，或填服务商接口里的模型名"
            onChange={(event) => setModel(event.target.value)}
          />
        </Label>
        {available.isPending && <p className="text-xs text-muted-foreground">正在读取这个服务商的模型列表…</p>}
        {available.isError && <p className="text-xs text-muted-foreground">{available.error.message}</p>}
        {available.data && (
          <ul
            aria-label="可选的模型"
            className="flex max-h-48 flex-col overflow-y-auto rounded-md border bg-background p-1"
          >
            {matches.length === 0 && <li className="px-2 py-1.5 text-xs text-muted-foreground">没有匹配的模型</li>}
            {matches.map((name) => (
              <li key={name}>
                <button
                  type="button"
                  aria-pressed={name === trimmed}
                  onClick={() => setModel(name)}
                  className={cn(
                    "w-full rounded px-2 py-1.5 text-left hover:bg-secondary",
                    name === trimmed && "bg-accent text-accent-foreground",
                  )}
                >
                  <ModelName model={name} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {shown && (
        <p
          role="status"
          className={cn("flex items-center gap-2 text-sm", shown.ok ? "text-success" : "text-destructive")}
        >
          {shown.ok ? (
            <CheckCircle2 aria-hidden="true" className="size-4 shrink-0" />
          ) : (
            <XCircle aria-hidden="true" className="size-4 shrink-0" />
          )}
          {shown.ok ? "连通正常" : `没有保存：${shown.message}`}
          {shown.latencyMs !== null && `（${shown.latencyMs} 毫秒）`}
        </p>
      )}
      {(test.isError || testAndSave.isError) && (
        <p role="alert" className="text-sm text-destructive">
          {(test.error ?? testAndSave.error)?.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        {canTest && (
          <Button type="button" variant="outline" size="sm" disabled={!trimmed || busy} onClick={() => test.mutate()}>
            只测试
          </Button>
        )}
        <Button type="button" size="sm" disabled={!trimmed || busy} onClick={() => testAndSave.mutate()}>
          {testAndSave.isPending ? "测试中…" : "测试并保存"}
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}

function ProvidersTab({ settings }: { settings: ModelSettings }) {
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Provider | "new" | null>(null);
  const remove = useMutation({
    mutationFn: (id: number) => deleteProvider(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["model-settings"] }),
  });

  return (
    <section aria-label="服务商" className="flex flex-col gap-3">
      <div className="flex items-end justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          OpenAI 兼容接口，如硅基流动、DeepSeek、自建的 vLLM / Ollama。密钥加密保存，只显示后 4 位。
        </p>
        <Can permission="model:update">
          <Button size="sm" onClick={() => setDialog("new")}>
            <Plus aria-hidden="true" className="size-4" />
            添加服务商
          </Button>
        </Can>
      </div>
      {remove.isError && <p className="text-sm text-destructive">{remove.error.message}</p>}
      <div className="rounded-lg border bg-card">
        <Table aria-label="服务商" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-56">名称</TableHead>
              <TableHead>地址</TableHead>
              <TableHead className="w-28">密钥</TableHead>
              <TableHead className="w-48">在用的用途</TableHead>
              <TableHead className="w-36 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.providers.map((provider) => (
              <TableRow key={provider.id ?? ENV}>
                <TableCell className="py-3">
                  <div className="truncate">{provider.name}</div>
                  {provider.source === "ENVIRONMENT" && <SourceBadge source="ENVIRONMENT" />}
                </TableCell>
                <TableCell className="truncate font-mono text-sm text-muted-foreground" title={provider.baseUrl}>
                  {provider.baseUrl}
                </TableCell>
                <TableCell className="font-mono text-sm">
                  {provider.apiKeyLast4 ? `••••${provider.apiKeyLast4}` : "无"}
                </TableCell>
                <TableCell className="text-sm">
                  {provider.usedBy.length === 0
                    ? "—"
                    : provider.usedBy.map((usage) => USAGE_LABELS[usage].name).join("、")}
                </TableCell>
                <TableCell className="text-right">
                  {provider.id !== null ? (
                    <Can permission="model:update">
                      <Button
                        variant="ghost"
                        size="sm"
                        aria-label={`编辑 ${provider.name}`}
                        onClick={() => setDialog(provider)}
                      >
                        编辑
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        aria-label={`删除 ${provider.name}`}
                        disabled={provider.usedBy.length > 0 || remove.isPending}
                        title={provider.usedBy.length > 0 ? "还有用途在用，先换掉才能删除" : undefined}
                        onClick={() => remove.mutate(provider.id!)}
                      >
                        删除
                      </Button>
                    </Can>
                  ) : (
                    <span className="text-xs text-muted-foreground">改环境变量后重启</span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Dialog open={dialog !== null} onOpenChange={(open) => !open && setDialog(null)}>
        {dialog && (
          <ProviderDialog
            provider={dialog === "new" ? null : dialog}
            masterKeyConfigured={settings.masterKeyConfigured}
            onDone={() => setDialog(null)}
          />
        )}
      </Dialog>
    </section>
  );
}

function ProviderDialog({
  provider,
  masterKeyConfigured,
  onDone,
}: {
  provider: Provider | null;
  masterKeyConfigured: boolean;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(provider?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? "");
  const [apiKey, setApiKey] = useState("");
  const save = useMutation({
    mutationFn: () => {
      const input = { name: name.trim(), baseUrl: baseUrl.trim(), ...(apiKey ? { apiKey } : {}) };
      return provider ? updateProvider(provider.id!, input) : createProvider(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["model-settings"] });
      await queryClient.invalidateQueries({ queryKey: ["available-models"] });
      onDone();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    save.mutate();
  }

  return (
    <DialogContent>
      <form aria-label={provider ? "编辑服务商" : "添加服务商"} onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader>
          <DialogTitle>{provider ? `编辑服务商：${provider.name}` : "添加服务商"}</DialogTitle>
          <DialogDescription>只支持 OpenAI 兼容接口。地址填到 /v1 为止。</DialogDescription>
        </DialogHeader>
        <Label className="flex flex-col gap-1 text-sm">
          名称
          <Input value={name} maxLength={30} required onChange={(event) => setName(event.target.value)} />
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          地址
          <Input
            value={baseUrl}
            required
            className="font-mono"
            placeholder="https://api.example.com/v1"
            onChange={(event) => setBaseUrl(event.target.value)}
          />
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          密钥
          <Input
            type="password"
            value={apiKey}
            autoComplete="off"
            disabled={!masterKeyConfigured}
            placeholder={
              !masterKeyConfigured
                ? "没有设置主密钥，不能保存密钥"
                : provider?.apiKeyLast4
                  ? `已保存 ••••${provider.apiKeyLast4}，留空表示不改`
                  : "不需要密钥时留空"
            }
            onChange={(event) => setApiKey(event.target.value)}
          />
        </Label>
        {save.isError && (
          <p role="alert" className="text-sm text-destructive">
            {save.error.message}
          </p>
        )}
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={save.isPending}>
            保存
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}

const PAGE_SIZE = 20;

function ChangesTab() {
  const [paging, setPaging] = useState({ page: 1, size: PAGE_SIZE });
  const [restoring, setRestoring] = useState<ModelChange | null>(null);
  const changes = useQuery({
    queryKey: ["model-changes", paging],
    queryFn: () => fetchModelChanges(paging),
    placeholderData: keepPreviousData,
  });

  return (
    <section aria-label="变更记录" className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        每次保存和恢复都记一条。恢复会把该用途换回这条记录之后的模型，确认后先测连通，通过才生效。
      </p>
      {changes.isError && (
        <p role="alert" className="text-sm text-destructive">
          {changes.error.message}
        </p>
      )}
      <div className="rounded-lg border bg-card">
        <Table aria-label="变更记录" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-32">时间</TableHead>
              <TableHead className="w-24">操作人</TableHead>
              <TableHead className="w-24">用途</TableHead>
              <TableHead>换成</TableHead>
              <TableHead>原来</TableHead>
              <TableHead className="w-16 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {changes.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-6 text-center text-muted-foreground">
                  还没有变更
                </TableCell>
              </TableRow>
            )}
            {changes.data?.items.map((change) => (
              <TableRow key={change.id}>
                <TableCell className="py-2.5 text-muted-foreground">
                  {new Date(change.changedAt).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" })}
                </TableCell>
                <TableCell className="truncate">{change.changedBy}</TableCell>
                <TableCell>
                  {USAGE_LABELS[change.usage].name}
                  {change.restoredFrom !== null && (
                    <Badge variant="outline" className="ml-1">
                      恢复操作
                    </Badge>
                  )}
                </TableCell>
                <TableCell>
                  <div className="truncate text-xs text-muted-foreground">{change.after.providerName}</div>
                  <ModelName model={change.after.model} />
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {change.before ? (
                    <>
                      <div className="truncate text-xs">{change.before.providerName}</div>
                      <ModelName model={change.before.model} />
                    </>
                  ) : (
                    "—"
                  )}
                </TableCell>
                <TableCell className="text-right">
                  <Can permission="model:update">
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`恢复${USAGE_LABELS[change.usage].name}为 ${change.after.model}`}
                      onClick={() => setRestoring(change)}
                    >
                      恢复
                    </Button>
                  </Can>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {changes.data && (
        <Pagination page={changes.data.page} size={changes.data.size} total={changes.data.total} onChange={setPaging} />
      )}
      <Dialog open={restoring !== null} onOpenChange={(open) => !open && setRestoring(null)}>
        {restoring && <RestoreDialog change={restoring} onDone={() => setRestoring(null)} />}
      </Dialog>
    </section>
  );
}

/** 恢复前确认：写明会把哪个用途换回哪个模型。 */
function RestoreDialog({ change, onDone }: { change: ModelChange; onDone: () => void }) {
  const queryClient = useQueryClient();
  const label = USAGE_LABELS[change.usage].name;
  const restore = useMutation({
    mutationFn: () => restoreChange(change.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["model-settings"] });
      await queryClient.invalidateQueries({ queryKey: ["model-changes"] });
      onDone();
    },
  });

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>恢复{label}模型</DialogTitle>
        <DialogDescription>
          把{label}换回 {change.after.providerName} / {change.after.model}。会先测连通，通过后立即生效，并记一条变更。
        </DialogDescription>
      </DialogHeader>
      {restore.isError && (
        <p role="alert" className="text-sm text-destructive">
          {restore.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        <Button type="button" size="sm" disabled={restore.isPending} onClick={() => restore.mutate()}>
          {restore.isPending ? "测试中…" : "确认恢复"}
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
