import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Lock, Plus, XCircle } from "lucide-react";
import { type FormEvent, useState } from "react";
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
import {
  createProvider,
  deleteProvider,
  fetchModelChanges,
  fetchModelSettings,
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

/**
 * 模型管理（#120、D-043）：各用途用哪个服务商的哪个模型、服务商（密钥只显示后 4 位）、变更记录。
 * 换模型在弹窗里做，先测连通、通过后才能保存，保存后立即生效；嵌入模型只显示。
 */
export function ModelsPage() {
  const settings = useQuery({ queryKey: ["model-settings"], queryFn: fetchModelSettings });

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-8 p-6 md:p-8">
      <h1 className="font-serif text-2xl font-semibold">模型管理</h1>
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
          <UsagesSection settings={settings.data} />
          <ProvidersSection settings={settings.data} />
          <ChangesSection />
        </>
      )}
    </main>
  );
}

function SourceBadge({ source }: { source: Source }) {
  return source === "DATABASE" ? <Badge>界面配置</Badge> : <Badge variant="outline">环境变量</Badge>;
}

function UsagesSection({ settings }: { settings: ModelSettings }) {
  const [editing, setEditing] = useState<UsageConfig | null>(null);

  return (
    <section aria-label="各用途的模型" className="flex flex-col gap-3">
      <div>
        <h2 className="text-lg font-medium">各用途的模型</h2>
        <p className="text-sm text-muted-foreground">
          在界面保存过的用途以这里为准，没保存过的用环境变量。保存后下一次请求就用新模型，不用重启。
        </p>
      </div>
      <div className="rounded-lg border bg-card">
        <Table aria-label="各用途的模型">
          <TableHeader>
            <TableRow>
              <TableHead className="w-56">用途</TableHead>
              <TableHead>服务商</TableHead>
              <TableHead>模型</TableHead>
              <TableHead className="w-28">来源</TableHead>
              <TableHead className="w-24 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.usages.map((usage) => (
              <TableRow key={usage.usage}>
                <TableCell className="py-3">
                  <div className="font-medium">{USAGE_LABELS[usage.usage].name}</div>
                  <div className="text-xs text-muted-foreground">{USAGE_LABELS[usage.usage].description}</div>
                </TableCell>
                <TableCell>{usage.providerName}</TableCell>
                <TableCell className="font-mono text-sm">{usage.model}</TableCell>
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
                <div className="text-xs">把资料和问题变成向量</div>
              </TableCell>
              <TableCell>{settings.embedding.providerName}</TableCell>
              <TableCell className="font-mono text-sm">
                {settings.embedding.model}（{settings.embedding.dimensions} 维）
              </TableCell>
              <TableCell>
                <Badge variant="outline">
                  <Lock aria-hidden="true" className="size-3" />
                  只读
                </Badge>
              </TableCell>
              <TableCell className="text-right text-xs">换嵌入要重建索引，后续版本提供</TableCell>
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

/** 换一个用途的模型：选服务商、填模型名，先测连通，通过后才能保存。 */
function UsageDialog({ usage, providers, onDone }: { usage: UsageConfig; providers: Provider[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const canTest = useCan("model:test");
  const [providerValue, setProviderValue] = useState(usage.providerId === null ? ENV : String(usage.providerId));
  const [model, setModel] = useState(usage.model);
  const [tested, setTested] = useState<{ key: string; result: TestResult } | null>(null);
  const providerId = providerValue === ENV ? null : Number(providerValue);
  const key = `${providerValue}|${model.trim()}`;
  const passed = tested?.key === key && tested.result.ok;

  const test = useMutation({
    mutationFn: () => testModel(usage.usage, providerId, model.trim()),
    onSuccess: (result) => setTested({ key, result }),
  });
  const save = useMutation({
    mutationFn: () => saveUsage(usage.usage, providerId, model.trim()),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["model-settings"] });
      await queryClient.invalidateQueries({ queryKey: ["model-changes"] });
      onDone();
    },
  });
  const label = USAGE_LABELS[usage.usage].name;

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>更换{label}模型</DialogTitle>
        <DialogDescription>{USAGE_LABELS[usage.usage].description}。保存前要先测通，保存后立即生效。</DialogDescription>
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
      <Label className="flex flex-col gap-1 text-sm">
        模型名称
        <Input
          value={model}
          className="font-mono"
          placeholder="服务商接口里的模型名，如 deepseek-chat"
          onChange={(event) => setModel(event.target.value)}
        />
      </Label>
      {tested?.key === key && (
        <p
          role="status"
          className={`flex items-center gap-2 text-sm ${tested.result.ok ? "text-success" : "text-destructive"}`}
        >
          {tested.result.ok ? (
            <CheckCircle2 aria-hidden="true" className="size-4" />
          ) : (
            <XCircle aria-hidden="true" className="size-4" />
          )}
          {tested.result.message}
          {tested.result.latencyMs !== null && `（${tested.result.latencyMs} 毫秒）`}
        </p>
      )}
      {(test.isError || save.isError) && (
        <p role="alert" className="text-sm text-destructive">
          {(test.error ?? save.error)?.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        {canTest && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!model.trim() || test.isPending}
            onClick={() => test.mutate()}
          >
            {test.isPending ? "测试中…" : "测试连通"}
          </Button>
        )}
        <Button type="button" size="sm" disabled={!passed || save.isPending} onClick={() => save.mutate()}>
          保存
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}

function ProvidersSection({ settings }: { settings: ModelSettings }) {
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Provider | "new" | null>(null);
  const remove = useMutation({
    mutationFn: (id: number) => deleteProvider(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["model-settings"] }),
  });

  return (
    <section aria-label="服务商" className="flex flex-col gap-3">
      <div className="flex items-end justify-between gap-3">
        <div>
          <h2 className="text-lg font-medium">服务商</h2>
          <p className="text-sm text-muted-foreground">
            OpenAI 兼容接口，如硅基流动、DeepSeek、自建的 vLLM / Ollama。密钥加密保存，只显示后 4 位。
          </p>
        </div>
        <Can permission="model:update">
          <Button size="sm" onClick={() => setDialog("new")}>
            <Plus aria-hidden="true" className="size-4" />
            添加服务商
          </Button>
        </Can>
      </div>
      {remove.isError && <p className="text-sm text-destructive">{remove.error.message}</p>}
      <div className="rounded-lg border bg-card">
        <Table aria-label="服务商">
          <TableHeader>
            <TableRow>
              <TableHead>名称</TableHead>
              <TableHead>地址</TableHead>
              <TableHead className="w-28">密钥</TableHead>
              <TableHead>在用的用途</TableHead>
              <TableHead className="w-36 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.providers.map((provider) => (
              <TableRow key={provider.id ?? ENV}>
                <TableCell className="py-3">
                  {provider.name}
                  {provider.source === "ENVIRONMENT" && (
                    <span className="ml-2">
                      <SourceBadge source="ENVIRONMENT" />
                    </span>
                  )}
                </TableCell>
                <TableCell className="font-mono text-sm text-muted-foreground">{provider.baseUrl}</TableCell>
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

const PAGE_SIZE = 10;

function ChangesSection() {
  const queryClient = useQueryClient();
  const [paging, setPaging] = useState({ page: 1, size: PAGE_SIZE });
  const changes = useQuery({
    queryKey: ["model-changes", paging],
    queryFn: () => fetchModelChanges(paging),
    placeholderData: keepPreviousData,
  });
  const restore = useMutation({
    mutationFn: (id: number) => restoreChange(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["model-settings"] });
      await queryClient.invalidateQueries({ queryKey: ["model-changes"] });
    },
  });

  return (
    <section aria-label="变更记录" className="flex flex-col gap-3">
      <div>
        <h2 className="text-lg font-medium">变更记录</h2>
        <p className="text-sm text-muted-foreground">恢复会把该用途换回这条记录之后的模型，同样先测连通。</p>
      </div>
      {(changes.isError || restore.isError) && (
        <p role="alert" className="text-sm text-destructive">
          {(changes.error ?? restore.error)?.message}
        </p>
      )}
      <div className="rounded-lg border bg-card">
        <Table aria-label="变更记录">
          <TableHeader>
            <TableRow>
              <TableHead className="w-40">时间</TableHead>
              <TableHead className="w-28">操作人</TableHead>
              <TableHead className="w-24">用途</TableHead>
              <TableHead>变更</TableHead>
              <TableHead className="w-24 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {changes.data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="py-6 text-center text-muted-foreground">
                  还没有变更
                </TableCell>
              </TableRow>
            )}
            {changes.data?.items.map((change) => (
              <TableRow key={change.id}>
                <TableCell className="py-2.5 text-muted-foreground">
                  {new Date(change.changedAt).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" })}
                </TableCell>
                <TableCell>{change.changedBy}</TableCell>
                <TableCell>{USAGE_LABELS[change.usage].name}</TableCell>
                <TableCell className="text-sm">
                  {change.before && (
                    <span className="text-muted-foreground">
                      {change.before.providerName} / <span className="font-mono">{change.before.model}</span>
                      {" → "}
                    </span>
                  )}
                  {change.after.providerName} / <span className="font-mono">{change.after.model}</span>
                  {change.restoredFrom !== null && (
                    <Badge variant="outline" className="ml-2">
                      恢复操作
                    </Badge>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  <Can permission="model:update">
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`恢复${USAGE_LABELS[change.usage].name}为 ${change.after.model}`}
                      disabled={restore.isPending}
                      onClick={() => restore.mutate(change.id)}
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
    </section>
  );
}
