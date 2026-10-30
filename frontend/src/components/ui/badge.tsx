import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

// shadcn/ui 的 Badge：权限、角色、状态等短标签。
const badgeVariants = cva("inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs whitespace-nowrap", {
  variants: {
    variant: {
      default: "border-transparent bg-accent text-accent-foreground",
      outline: "border-input text-muted-foreground",
      success: "border-transparent bg-secondary text-success",
      destructive: "border-transparent bg-secondary text-destructive",
    },
  },
  defaultVariants: { variant: "default" },
});

export function Badge({ className, variant, ...props }: ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" className={cn(badgeVariants({ variant }), className)} {...props} />;
}
