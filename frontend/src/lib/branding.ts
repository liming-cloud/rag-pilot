import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { json } from "./api";

/**
 * 产品名称和登录页文案（D-042），由部署方配置，后端 GET /api/auth/branding 给出，不需要登录。
 * 内核不写死行业名称：界面上出现产品名称的地方都从这里取。
 */
export type Branding = {
  /** 产品名称，显示在登录页、顶栏和浏览器标题上。 */
  name: string;
  /** 登录页名称下方的一句话（slogan），可以为空。 */
  tagline: string;
  /** 登录按钮下方的提示。 */
  loginHint: string;
};

/** 读取失败时的兜底，与后端没有配置时的默认值一致。默认值只写在这里。 */
export const DEFAULT_BRANDING: Branding = {
  name: "明答",
  tagline: "每个回答，都有出处",
  loginHint: "账号由管理员开通；忘记密码请联系管理员。",
};

export async function fetchBranding(): Promise<Branding> {
  return json(await fetch("/api/auth/branding"), "读取产品名称失败");
}

/** 读不到时用默认值，不让名称影响登录。顺便把浏览器标题设成产品名称。 */
export function useBranding(): Branding {
  const branding = useQuery({ queryKey: ["branding"], queryFn: fetchBranding, staleTime: Infinity, retry: false });
  const value = branding.data ?? DEFAULT_BRANDING;
  useEffect(() => {
    document.title = value.name;
  }, [value.name]);
  return value;
}
