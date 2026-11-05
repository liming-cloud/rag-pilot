import { Badge } from "@/components/ui/badge";
import { type Source } from "./api";

export function SourceBadge({ source }: { source: Source }) {
  return source === "DATABASE" ? <Badge>界面配置</Badge> : <Badge variant="outline">环境变量</Badge>;
}

/**
 * 模型名常带组织前缀（如 deepseek-ai/DeepSeek-V3）：名称在上、前缀用小字放在下面，窄列里也能看清关键部分；
 * 不折行，太长时截断，悬停看全名。
 */
export function ModelName({ model }: { model: string }) {
  const slash = model.lastIndexOf("/");
  return (
    <span title={model} className="flex min-w-0 flex-col">
      <span className="truncate font-mono text-sm">{model.slice(slash + 1)}</span>
      {slash > 0 && <span className="truncate text-xs text-muted-foreground">{model.slice(0, slash)}</span>}
    </span>
  );
}
