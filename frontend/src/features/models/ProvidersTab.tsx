import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { type FormEvent, useState } from "react";
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import {
  createProvider,
  deleteProvider,
  modelKeys,
  type ModelSettings,
  type Provider,
  updateProvider,
  USAGE_LABELS,
} from "./api";
import { SourceBadge } from "./ModelLabels";
import { ENV } from "./UsagesTab";

export function ProvidersTab({ settings }: { settings: ModelSettings }) {
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Provider | "new" | null>(null);
  const [removing, setRemoving] = useState<Provider | null>(null);
  const remove = useMutation({
    mutationFn: (id: number) => deleteProvider(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: modelKeys.settings });
      setRemoving(null);
    },
  });

  return (
    <section aria-label="服务商" className="flex flex-col gap-3">
      <div className="flex items-end justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          OpenAI 兼容接口，如硅基流动、DeepSeek、自建的 vLLM / Ollama。密钥加密保存，只显示后 4 位。
        </p>
        <Can permission={PERMISSIONS.MODEL_UPDATE}>
          <Button size="sm" onClick={() => setDialog("new")}>
            <Plus aria-hidden="true" className="size-4" />
            添加服务商
          </Button>
        </Can>
      </div>
      <div className="rounded-lg border bg-card">
        <Table aria-label="服务商" className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead>服务商</TableHead>
              <TableHead className="w-24">密钥</TableHead>
              <TableHead className="w-36">在用的用途</TableHead>
              <TableHead className="w-32 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.providers.map((provider) => (
              <TableRow key={provider.id ?? ENV}>
                <TableCell className="py-3">
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate" title={provider.name}>
                      {provider.name}
                    </span>
                    {provider.source === "ENVIRONMENT" && <SourceBadge source="ENVIRONMENT" />}
                  </div>
                  <div className="truncate font-mono text-xs text-muted-foreground" title={provider.baseUrl}>
                    {provider.baseUrl}
                  </div>
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
                    <Can permission={PERMISSIONS.MODEL_UPDATE}>
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
                        disabled={provider.usedBy.length > 0}
                        title={provider.usedBy.length > 0 ? "还有用途在用，先换掉才能删除" : undefined}
                        onClick={() => {
                          remove.reset();
                          setRemoving(provider);
                        }}
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
      <Dialog open={removing !== null} onOpenChange={(open) => !open && setRemoving(null)}>
        {removing && (
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>删除服务商“{removing.name}”？</DialogTitle>
              <DialogDescription>保存的地址和密钥会一并删除，删除后要用得重新添加并录入密钥。</DialogDescription>
            </DialogHeader>
            <ErrorMessage error={remove.error} />
            <DialogFooter>
              <Button variant="outline" onClick={() => setRemoving(null)}>
                取消
              </Button>
              <Button onClick={() => remove.mutate(removing.id!)} disabled={remove.isPending}>
                删除
              </Button>
            </DialogFooter>
          </DialogContent>
        )}
      </Dialog>
    </section>
  );
}

export function ProviderDialog({
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
      await queryClient.invalidateQueries({ queryKey: modelKeys.settings });
      await queryClient.invalidateQueries({ queryKey: modelKeys.available });
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
        <ErrorMessage error={save.error} />
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
