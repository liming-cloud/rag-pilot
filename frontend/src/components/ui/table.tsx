import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

// shadcn/ui 的 Table。与原版不同：Table 不再包一层滚动容器，由页面决定滚动区域，否则吸顶的表头会失效。
export function Table({ className, ...props }: ComponentProps<"table">) {
  return <table data-slot="table" className={cn("w-full text-sm", className)} {...props} />;
}

export function TableHeader({ className, ...props }: ComponentProps<"thead">) {
  return <thead data-slot="table-header" className={cn("text-left text-muted-foreground", className)} {...props} />;
}

export function TableBody({ className, ...props }: ComponentProps<"tbody">) {
  return <tbody data-slot="table-body" className={cn("divide-y", className)} {...props} />;
}

export function TableRow(props: ComponentProps<"tr">) {
  return <tr data-slot="table-row" {...props} />;
}

export function TableHead({ className, ...props }: ComponentProps<"th">) {
  return <th data-slot="table-head" className={cn("px-3 py-2 font-normal", className)} {...props} />;
}

export function TableCell({ className, ...props }: ComponentProps<"td">) {
  return <td data-slot="table-cell" className={cn("px-3 py-1.5", className)} {...props} />;
}
