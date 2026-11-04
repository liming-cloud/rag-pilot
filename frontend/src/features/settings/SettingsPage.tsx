import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { ErrorMessage } from "@/lib/errors";
import { cn } from "@/lib/utils";
import { ItemsTab } from "./ItemsTab";
import { PackTab } from "./PackTab";
import { fetchSettings, settingsKeys } from "./api";

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
  const settings = useQuery({ queryKey: settingsKeys.all, queryFn: fetchSettings });
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
      <ErrorMessage error={settings.error} className="text-base" />
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
