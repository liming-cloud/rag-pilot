import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

/** 管理页的外壳：居中、最宽 1600px、统一内边距，内容竖排；className 可以改间距或换成网格。 */
export function PageShell({ className, ...props }: ComponentProps<"main">) {
  return <main className={cn("mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-6 md:p-8 xl:px-10", className)} {...props} />;
}
