import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

// shadcn/ui 的 Table。与原版不同：Table 不再包一层滚动容器，由页面决定滚动区域，否则吸顶的表头会失效。
export function Table({ className, ...props }: ComponentProps<"table">) {
  return <table data-slot="table" className={cn("w-full text-sm", className)} {...props} />;
}

export function TableHeader({ className, ...props }: ComponentProps<"thead">) {
  return <thead data-slot="table-header" className={cn("border-b bg-muted/50 text-left text-xs tracking-wide text-muted-foreground", className)} {...props} />;
}

export function TableBody({ className, ...props }: ComponentProps<"tbody">) {
  return <tbody data-slot="table-body" className={cn("divide-y divide-border/70 [&_tr]:transition-colors [&_tr:hover]:bg-accent/40", className)} {...props} />;
}

export function TableRow(props: ComponentProps<"tr">) {
  return <tr data-slot="table-row" {...props} />;
}

export function TableHead({ className, ...props }: ComponentProps<"th">) {
  return <th data-slot="table-head" className={cn("px-4 py-3 font-medium whitespace-nowrap", className)} {...props} />;
}

export function TableCell({ className, ...props }: ComponentProps<"td">) {
  return <td data-slot="table-cell" className={cn("px-4 py-3 align-middle", className)} {...props} />;
}
