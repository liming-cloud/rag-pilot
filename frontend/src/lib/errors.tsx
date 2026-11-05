import { cn } from "./utils";

/**
 * 出错提示（#145）：一律带 role="alert"，读屏软件会立即读出。error 可以是请求抛出的错误（显示它的说明，
 * 后端的问题详情已在 lib/api.ts 里转成中文说明），也可以直接给一句话；没有错误时什么也不显示。
 */
export function ErrorMessage({ error, className }: { error: unknown; className?: string }) {
  const message = error instanceof Error ? error.message : typeof error === "string" ? error : null;
  if (!message) return null;
  return (
    <p role="alert" className={cn("text-sm text-destructive", className)}>
      {message}
    </p>
  );
}
