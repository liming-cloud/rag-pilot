import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorMessage } from "@/lib/errors";
import { decode, readBytes } from "@/lib/csv";
import { evaluationKeys, importQuestions } from "./api";
import { QUESTION_HEADER, questionTemplate, toQuestionRows } from "./csv";

/**
 * 从 CSV 导入标准题（#224）：知识库、期望文档按名称填，后端按当前账号能访问的库匹配；
 * 匹配不上的行跳过并列出原因，其余照常导入。
 */
export function ImportDialog({ setId, onDone }: { setId: number; onDone: () => void }) {
  const queryClient = useQueryClient();
  const submit = useMutation({
    mutationFn: async (file: File) => {
      const parsed = toQuestionRows(decode(await readBytes(file)));
      if ("error" in parsed) throw new Error(parsed.error);
      return importQuestions(setId, parsed.rows);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: evaluationKeys.all }),
  });

  function downloadTemplate() {
    const url = URL.createObjectURL(questionTemplate());
    const link = document.createElement("a");
    link.href = url;
    link.download = "标准题模板.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <DialogContent className="max-w-xl">
      <DialogHeader>
        <DialogTitle>导入标准题</DialogTitle>
        <DialogDescription>
          列为“{QUESTION_HEADER.join("，")}”。期望文档多个时用“|”分隔，留空表示应当拒答；命中方式填“全部”或“任一”，留空为全部。
        </DialogDescription>
      </DialogHeader>
      {submit.data ? (
        <div className="flex flex-col gap-2 text-sm" role="status">
          <p>
            导入 {submit.data.imported} 道题{submit.data.skipped.length > 0 && `，跳过 ${submit.data.skipped.length} 行`}。
          </p>
          {submit.data.skipped.length > 0 && (
            <ul className="max-h-48 overflow-y-auto rounded-md border p-2 text-muted-foreground">
              {submit.data.skipped.map((row) => (
                <li key={row.line}>
                  第 {row.line} 行：{row.reason}
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <Button variant="outline" size="sm" className="self-start" onClick={downloadTemplate}>
            <Download aria-hidden="true" className="size-4" />
            下载模板
          </Button>
          <Label className="flex flex-col gap-1">
            选择 CSV 文件
            <Input
              type="file"
              accept=".csv,text/csv"
              disabled={submit.isPending}
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) submit.mutate(file);
              }}
            />
          </Label>
        </div>
      )}
      <ErrorMessage error={submit.error} />
      <DialogFooter>
        <Button variant={submit.data ? "default" : "ghost"} onClick={onDone}>
          {submit.data ? "完成" : "取消"}
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
