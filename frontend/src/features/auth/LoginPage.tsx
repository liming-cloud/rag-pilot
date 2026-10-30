import { BadgeCheck, FileSearch, ShieldCheck } from "lucide-react";
import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ApiError } from "@/lib/api";
import { useBranding } from "@/lib/branding";
import { BadCredentialsError, login } from "./api";
import { TrustIllustration } from "./TrustIllustration";

/** 内核的产品主张，不随行业变（D-042）。 */
const VALUES = [
  { icon: FileSearch, title: "有出处", text: "每个回答都标出引用的原文，点开就能核对。" },
  { icon: ShieldCheck, title: "不越权", text: "只在授权给你的资料里找答案，权限由系统判定。" },
  { icon: BadgeCheck, title: "不编造", text: "资料里没有依据时直接说明，不拿猜测充当答案。" },
];

/**
 * 登录页（D-042）：PC 端左右分栏，左侧品牌区（名称、slogan、插图、核心价值），右侧登录卡片；窄屏只显示登录卡片。
 * 名称、slogan 和按钮下方提示由部署配置。账号由管理员创建，不提供注册和网页找回密码。
 */
export function LoginPage() {
  const navigate = useNavigate();
  const branding = useBranding();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const me = await login(username.trim(), password);
      navigate(me.mustChangePassword ? "/change-password" : "/chat", { replace: true });
    } catch (e) {
      setError(
        e instanceof BadCredentialsError || (e instanceof ApiError && e.code === "IDENTITY_LOCKED")
          ? e.message
          : "暂时无法登录，请稍后重试。",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.15fr_1fr]">
      <section
        aria-label="产品介绍"
        className="hidden flex-col justify-between gap-10 bg-secondary px-14 py-12 lg:flex"
      >
        <div className="flex flex-col gap-3">
          <p className="font-serif text-3xl font-semibold">{branding.name}</p>
          {branding.tagline && <p className="text-lg text-muted-foreground">{branding.tagline}</p>}
        </div>
        <TrustIllustration className="w-full max-w-lg self-center" />
        <ul className="grid grid-cols-3 gap-6">
          {VALUES.map(({ icon: Icon, title, text }) => (
            <li key={title} className="flex flex-col gap-1.5">
              <span className="flex items-center gap-2 font-medium">
                <Icon aria-hidden="true" className="size-4 text-brand" />
                {title}
              </span>
              <span className="text-sm text-muted-foreground">{text}</span>
            </li>
          ))}
        </ul>
      </section>
      <main className="flex items-center justify-center p-6">
        <div className="flex w-full max-w-sm flex-col gap-6 rounded-lg border bg-card p-8 shadow-sm">
          <div className="flex flex-col gap-1">
            <h1 className="font-serif text-2xl font-semibold">登录{branding.name}</h1>
            {branding.tagline && <p className="text-sm text-muted-foreground lg:hidden">{branding.tagline}</p>}
          </div>
          <form onSubmit={submit} className="flex flex-col gap-4">
            <Label className="flex flex-col gap-1">
              用户名
              <Input
                className="h-11 text-base"
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
              />
            </Label>
            <Label className="flex flex-col gap-1">
              密码
              <Input
                className="h-11 text-base"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
            </Label>
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
            <Button type="submit" className="h-11" disabled={submitting}>
              登录
            </Button>
          </form>
          <p className="text-center text-sm text-muted-foreground">{branding.loginHint}</p>
        </div>
      </main>
    </div>
  );
}
