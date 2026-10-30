import { useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useMe } from "@/lib/permissions";
import { changePassword } from "./api";

/**
 * 修改密码。管理员重置过密码时，登录后会先到这里，改完才能用其他功能（D-038）。
 * 规则：8–64 位，不能与用户名相同，不强制复杂度。
 */
export function ChangePasswordPage() {
  const me = useMe();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [form, setForm] = useState({ oldPassword: "", newPassword: "", confirm: "" });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const forced = me.data?.mustChangePassword ?? false;

  function check(): string | null {
    if (form.newPassword.length < 8 || form.newPassword.length > 64) return "新密码要 8 到 64 位";
    if (form.newPassword === me.data?.username) return "新密码不能与用户名相同";
    if (form.newPassword !== form.confirm) return "两次输入的新密码不一致";
    return null;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const problem = check();
    if (problem) {
      setError(problem);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await changePassword(form.oldPassword, form.newPassword);
      await queryClient.invalidateQueries({ queryKey: ["me"] });
      navigate("/chat", { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : "修改密码失败");
    } finally {
      setSubmitting(false);
    }
  }

  const field = (key: keyof typeof form) => ({
    value: form[key],
    onChange: (event: { target: { value: string } }) => setForm({ ...form, [key]: event.target.value }),
  });

  return (
    <main className="mx-auto flex h-full max-w-sm flex-col justify-center gap-6 p-6">
      <div className="flex flex-col gap-1">
        <h1 className="font-serif text-2xl font-semibold">修改密码</h1>
        {forced && <p className="text-sm text-muted-foreground">管理员重置了你的密码，请先设置新密码再继续使用。</p>}
      </div>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Label className="flex flex-col gap-1">
          {forced ? "管理员给你的临时密码" : "当前密码"}
          <Input type="password" autoComplete="current-password" required {...field("oldPassword")} />
        </Label>
        <Label className="flex flex-col gap-1">
          新密码（8–64 位，不能与用户名相同）
          <Input type="password" autoComplete="new-password" required {...field("newPassword")} />
        </Label>
        <Label className="flex flex-col gap-1">
          再输一次新密码
          <Input type="password" autoComplete="new-password" required {...field("confirm")} />
        </Label>
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <Button type="submit" disabled={submitting}>
          保存新密码
        </Button>
      </form>
    </main>
  );
}
