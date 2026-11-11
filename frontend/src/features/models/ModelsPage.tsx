import { useQuery } from "@tanstack/react-query";
import { AlertTriangle } from "lucide-react";
import { useSearchParams } from "react-router";
import { PageShell } from "@/components/ui/page-shell";
import { ErrorMessage } from "@/lib/errors";
import { cn } from "@/lib/utils";
import { ChangesTab } from "./ChangesTab";
import { ProvidersTab } from "./ProvidersTab";
import { UsagesTab } from "./UsagesTab";
import { UsageStatsTab } from "./UsageStatsTab";
import { fetchModelSettings, modelKeys } from "./api";

const TABS = [
  { key: "providers", label: "服务商" },
  { key: "usages", label: "用途与模型" },
  { key: "stats", label: "用量" },
  { key: "changes", label: "变更记录" },
] as const;

type Tab = (typeof TABS)[number]["key"];

/**
 * 模型管理（#120、D-043）：分“服务商”“用途与模型”“用量”“变更记录”四个标签（先有服务商，才能给用途选模型），标签记在地址里，刷新后不丢。
 * 换模型在弹窗里做：从服务商的模型列表里选（不提供列表时手填），“测试并保存”一步完成，测不通不保存；
 * 恢复变更前先确认。嵌入模型只显示。
 */
export function ModelsPage() {
  const settings = useQuery({ queryKey: modelKeys.settings, queryFn: fetchModelSettings });
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.some((item) => item.key === params.get("tab")) ? (params.get("tab") as Tab) : "providers";

  return (
    <PageShell>
      <div>
        <h1 className="font-display text-2xl font-semibold tracking-tight">模型管理</h1>
        <p className="text-sm text-muted-foreground">
          在界面保存过的用途以这里为准，没保存过的用环境变量。保存后下一次请求就用新模型，不用重启。
        </p>
      </div>
      <ErrorMessage error={settings.error} className="text-base" />
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
                onClick={() => setParams(item.key === "providers" ? {} : { tab: item.key })}
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
          {tab === "stats" && <UsageStatsTab />}
          {tab === "changes" && <ChangesTab />}
        </>
      )}
    </PageShell>
  );
}
