import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/** 合并 Tailwind 类名，后出现的同类工具类覆盖先出现的。 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** 界面上的时间统一显示为“yyyy-MM-dd HH:mm:ss”（浏览器时区），如“2026-11-05 14:30:00”。 */
export function formatDateTime(value: string) {
  const date = new Date(value);
  const two = (part: number) => String(part).padStart(2, "0");
  return (
    `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())} ` +
    `${two(date.getHours())}:${two(date.getMinutes())}:${two(date.getSeconds())}`
  );
}
