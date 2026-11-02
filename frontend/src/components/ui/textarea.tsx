import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

// shadcn/ui 的 Textarea，样式与 Input 一致（卡片底色、聚焦时描边）；宽度和行数由页面决定。
export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "min-h-16 rounded-md border border-input bg-card px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}
