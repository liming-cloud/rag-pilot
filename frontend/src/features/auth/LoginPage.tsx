import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { BadCredentialsError, login } from "./api";

/** 登录页，手机优先。账号由管理员创建，这里不提供注册和找回密码。 */
export function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(username.trim(), password);
      navigate("/chat", { replace: true });
    } catch (e) {
      setError(e instanceof BadCredentialsError ? e.message : "暂时无法登录，请稍后重试。");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto flex h-dvh max-w-sm flex-col justify-center gap-6 p-6">
      <h1 className="text-center font-serif text-2xl font-semibold">门店运营知识库</h1>
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
        <Button type="submit" disabled={submitting}>
          登录
        </Button>
      </form>
      <p className="text-center text-sm text-muted-foreground">账号由店长或总部管理员开通。</p>
    </main>
  );
}
