import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

// shadcn/ui 的 NativeSelect：保留浏览器原生下拉的交互和无障碍语义，只统一外观（与 Input 一致）。
export function NativeSelect({ className, ...props }: ComponentProps<"select">) {
  return (
    <select
      data-slot="native-select"
      className={cn(
        "h-9 min-w-0 rounded-md border border-input bg-card px-3 text-sm outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}

export function NativeSelectOption(props: ComponentProps<"option">) {
  return <option data-slot="native-select-option" {...props} />;
}
