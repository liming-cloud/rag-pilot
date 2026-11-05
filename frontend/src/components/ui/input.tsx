import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

// shadcn/ui 的 Input，默认样式取自页面原有的输入框（高 36px、卡片底色、聚焦时描边）；宽度由页面决定。
export function Input({ className, type, ...props }: ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        "h-10 min-w-0 rounded-md border border-input bg-card px-3 text-sm shadow-sm outline-none transition-shadow focus:border-primary focus:ring-4 focus:ring-ring/20 disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}
