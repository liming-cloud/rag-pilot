import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Lock, XCircle } from "lucide-react";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS, useCan } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { SourceBadge, ModelName } from "./ModelLabels";
import {
  fetchAvailableModels,
  modelKeys,
  type ModelSettings,
  type Provider,
  saveUsage,
  testModel,
  type TestResult,
  USAGE_LABELS,
  type UsageConfig,
} from "./api";

export function UsagesTab({ settings }: { settings: ModelSettings }) {
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
                  <Can permission={PERMISSIONS.MODEL_UPDATE}>
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

export const ENV = "env";

/**
 * 换一个用途的模型：选服务商，从它的模型列表里选或手填模型名；“测试并保存”先测、通了才保存，
 * 也可以“只测试”。
 */
export function UsageDialog({ usage, providers, onDone }: { usage: UsageConfig; providers: Provider[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const canTest = useCan(PERMISSIONS.MODEL_TEST);
  const [providerValue, setProviderValue] = useState(usage.providerId === null ? ENV : String(usage.providerId));
  const [model, setModel] = useState(usage.model);
  const [result, setResult] = useState<{ key: string; value: TestResult } | null>(null);
  const providerId = providerValue === ENV ? null : Number(providerValue);
  const trimmed = model.trim();
  const key = `${providerValue}|${trimmed}`;
  const available = useQuery({
    queryKey: modelKeys.availableFor(providerValue),
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
      await queryClient.invalidateQueries({ queryKey: modelKeys.settings });
      await queryClient.invalidateQueries({ queryKey: modelKeys.changes });
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
      <ErrorMessage error={test.error ?? testAndSave.error} />
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
