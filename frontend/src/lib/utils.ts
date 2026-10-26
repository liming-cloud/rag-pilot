import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/** 合并 Tailwind 类名，后出现的同类工具类覆盖先出现的。 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
