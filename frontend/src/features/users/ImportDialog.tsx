import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Role } from "@/features/roles/api";
import { roleKeys } from "@/features/roles/api";
import { ErrorMessage } from "@/lib/errors";
import { checkImport, type ImportProblem, type ImportRow, importUsers, userKeys } from "./api";
import { decode, MAX_ROWS, readBytes, template, toRows } from "./csv";

type Stage =
  | { kind: "choose"; error: string | null }
  | { kind: "review"; fileName: string; rows: ImportRow[]; problems: ImportProblem[] }
  | { kind: "done"; imported: number };

/**
 * 批量导入账号（#124）：下载模板 → 选文件 → 预检逐行列出问题 → 填统一初始密码 → 确认导入。
 * 有任何一行有问题就不能导入，改好文件重新选；文件里不放密码，所有导入的账号首次登录必须先改密码。
 */
export function ImportDialog({ roles, onDone }: { roles: Role[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [stage, setStage] = useState<Stage>({ kind: "choose", error: null });
  const [password, setPassword] = useState("");

  const check = useMutation({
    mutationFn: async (file: File) => {
      const parsed = toRows(decode(await readBytes(file)));
      if ("error" in parsed) return { kind: "choose", error: parsed.error } as const;
      return {
        kind: "review",
        fileName: file.name,
        rows: parsed.rows,
        problems: await checkImport(parsed.rows),
      } as const;
    },
    onSuccess: setStage,
  });
  const submit = useMutation({
    mutationFn: (rows: ImportRow[]) => importUsers(rows, password),
    onSuccess: async ({ imported }) => {
      await queryClient.invalidateQueries({ queryKey: userKeys.all });
      await queryClient.invalidateQueries({ queryKey: roleKeys.all });
      setStage({ kind: "done", imported });
    },
  });

  function choose(file: File | undefined) {
    if (file) check.mutate(file);
  }

  return (
    <DialogContent className="max-w-3xl">
      <DialogHeader>
        <DialogTitle>批量导入账号</DialogTitle>
        <DialogDescription>
          按模板填写用户名、显示名和角色（多个角色用英文分号 ; 分隔），一次最多 {MAX_ROWS}{" "}
          个。已存在的用户名不会被修改。
        </DialogDescription>
      </DialogHeader>
      {stage.kind === "done" ? (
        <>
          <p className="text-sm">
            已导入 {stage.imported} 个账号。请把用户名和初始密码交给员工，他们首次登录时需要先改成自己的密码。
          </p>
          <DialogFooter>
            <Button size="sm" onClick={onDone}>
              完成
            </Button>
          </DialogFooter>
        </>
      ) : (
        <>
          <ChooseFile
            roles={roles}
            busy={check.isPending}
            fileName={stage.kind === "review" ? stage.fileName : null}
            onChoose={choose}
          />
          {stage.kind === "choose" && (stage.error ?? check.error) && (
            <p role="alert" className="text-sm text-destructive">
              {stage.error ?? check.error?.message}
            </p>
          )}
          {stage.kind === "review" && (
            <Review
              key={check.submittedAt}
              stage={stage}
              password={password}
              onPassword={setPassword}
              busy={submit.isPending}
              error={submit.error?.message ?? null}
              onCancel={onDone}
              onSubmit={() => submit.mutate(stage.rows)}
            />
          )}
          {stage.kind === "choose" && (
            <DialogFooter>
              <Button variant="ghost" size="sm" onClick={onDone}>
                取消
              </Button>
            </DialogFooter>
          )}
        </>
      )}
    </DialogContent>
  );
}

function downloadTemplate() {
  const url = URL.createObjectURL(template());
  const link = document.createElement("a");
  link.href = url;
  link.download = "账号导入模板.csv";
  link.click();
  URL.revokeObjectURL(url);
}

/** 第一步：下载模板、选文件；列出可填的角色名，免得填错。 */
function ChooseFile({
  roles,
  busy,
  fileName,
  onChoose,
}: {
  roles: Role[];
  busy: boolean;
  fileName: string | null;
  onChoose: (file: File | undefined) => void;
}) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" onClick={downloadTemplate}>
          <Download aria-hidden="true" className="size-4" />
          下载模板
        </Button>
        <Button asChild size="sm" disabled={busy}>
          <Label>
            {fileName ? "重新选择文件" : "选择文件"}
            <Input
              type="file"
              accept=".csv,text/csv"
              aria-label="选择导入文件"
              className="sr-only"
              onChange={(event) => {
                onChoose(event.target.files?.[0]);
                event.target.value = "";
              }}
            />
          </Label>
        </Button>
        {busy && <span className="text-sm text-muted-foreground">正在检查…</span>}
        {fileName && !busy && <span className="truncate text-sm text-muted-foreground">{fileName}</span>}
      </div>
      <p className="text-xs text-muted-foreground">
        可填的角色：{roles.map((role) => role.name).join("、")}。只能分配不超过你自己权限的角色。用 Excel 编辑后“另存为
        CSV”即可，UTF-8 和 GBK 编码都能识别。
      </p>
    </div>
  );
}

/** 第二步：预检结果。有问题时默认只看有问题的行；全部通过才显示初始密码和导入按钮。 */
function Review({
  stage,
  password,
  onPassword,
  busy,
  error,
  onCancel,
  onSubmit,
}: {
  stage: Extract<Stage, { kind: "review" }>;
  password: string;
  onPassword: (password: string) => void;
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  const problemsByLine = new Map<number, string[]>();
  for (const problem of stage.problems) {
    problemsByLine.set(problem.line, [...(problemsByLine.get(problem.line) ?? []), problem.message]);
  }
  const badLines = problemsByLine.size;
  const [onlyProblems, setOnlyProblems] = useState(badLines > 0);
  const shown = onlyProblems ? stage.rows.filter((row) => problemsByLine.has(row.line)) : stage.rows;

  return (
    <form
      aria-label="确认导入"
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        if (badLines === 0) onSubmit();
      }}
    >
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span role="status" className={badLines > 0 ? "text-destructive" : "text-success"}>
          {badLines > 0
            ? `共 ${stage.rows.length} 行，${badLines} 行有问题。改好文件后重新选择，全部通过才能导入。`
            : `共 ${stage.rows.length} 行，全部通过检查。`}
        </span>
        {badLines > 0 && (
          <Label className="ml-auto flex items-center gap-2 font-normal">
            <Checkbox checked={onlyProblems} onCheckedChange={(checked) => setOnlyProblems(checked === true)} />
            只看有问题的行
          </Label>
        )}
      </div>
      <div className="max-h-72 overflow-y-auto rounded-lg border">
        <Table aria-label="导入预检">
          <TableHeader>
            <TableRow>
              <TableHead className="w-12">行</TableHead>
              <TableHead>用户名</TableHead>
              <TableHead>显示名</TableHead>
              <TableHead>角色</TableHead>
              <TableHead>问题</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((row) => (
              <TableRow key={row.line}>
                <TableCell className="text-muted-foreground">{row.line}</TableCell>
                <TableCell className="max-w-40 min-w-24 wrap-anywhere">{row.username}</TableCell>
                <TableCell className="max-w-40 wrap-anywhere">{row.displayName}</TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {row.roleNames.map((name) => (
                      <Badge key={name} variant="outline">
                        {name}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
                <TableCell className="text-xs text-destructive">
                  {problemsByLine.get(row.line)?.join("；") ?? <span className="text-success">通过</span>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {badLines === 0 && (
        <Label className="flex flex-col gap-1">
          统一初始密码（8–64 位）
          <Input
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={64}
            required
            className="max-w-xs"
            value={password}
            onChange={(event) => onPassword(event.target.value)}
          />
          <span className="text-xs font-normal text-muted-foreground">
            所有导入的账号都用这个密码首次登录，登录后必须先改成自己的密码。
          </span>
        </Label>
      )}
      <ErrorMessage error={error} />
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onCancel}>
          取消
        </Button>
        {badLines === 0 && (
          <Button type="submit" size="sm" disabled={busy}>
            导入 {stage.rows.length} 个账号
          </Button>
        )}
      </DialogFooter>
    </form>
  );
}
